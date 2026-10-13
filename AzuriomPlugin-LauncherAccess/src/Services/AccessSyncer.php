<?php

namespace Azuriom\Plugin\LauncherAccess\Services;

use Azuriom\Models\User;
use Azuriom\Plugin\LauncherAccess\Models\LedgerRow;
use Azuriom\Plugin\LauncherAccess\Models\Profile;
use Azuriom\Plugin\LauncherAccess\Models\Rule;
use Azuriom\Plugin\LauncherAccess\Support\DesiredBuilder;
use Azuriom\Plugin\LauncherAccess\Support\PermissionFormat;
use Azuriom\Plugin\LauncherAccess\Support\SyncPlanner;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Log;

/**
 * Writes the permissions the admin chose into the table LaunchServer reads (user_permissions).
 *
 * It is idempotent and only ever inserts or deletes rows of the two formats in {@see PermissionFormat},
 * and only deletes rows it inserted itself (the ledger table).
 */
class AccessSyncer
{
    public const CONNECTION = 'launcheraccess';

    private const CHUNK = 200;

    public function __construct(
        private readonly string $table,
        private readonly string $uuidColumn,
        private readonly string $permissionColumn,
        private readonly int $maxLength = PermissionFormat::DEFAULT_MAX_LENGTH,
        private readonly string $uuidFormat = 'auto',
    ) {
        if (! in_array($uuidFormat, ['auto', 'dashless', 'dashed'], true)) {
            throw new \InvalidArgumentException('Invalid uuid format: '.$uuidFormat);
        }

        foreach ([$table, $uuidColumn, $permissionColumn] as $identifier) {
            if (! self::isIdentifier($identifier)) {
                throw new \InvalidArgumentException('Invalid table or column name: '.$identifier);
            }
        }
    }

    public static function isIdentifier(string $value): bool
    {
        return (bool) preg_match('/^[A-Za-z0-9_]{1,64}$/D', $value);
    }

    public static function fromSettings(): self
    {
        return new self(
            (string) setting('launcheraccess.table', 'user_permissions'),
            (string) setting('launcheraccess.uuid_column', 'uuid'),
            (string) setting('launcheraccess.permission_column', 'name'),
            (int) setting('launcheraccess.max_length', PermissionFormat::DEFAULT_MAX_LENGTH),
            (string) setting('launcheraccess.uuid_format', 'auto'),
        );
    }

    private ?string $resolvedFormat = null;

    /**
     * "dashless" or "dashed": how player uuids are spelled in user_permissions.
     * auto: the way users.game_id is stored (LaunchServer finds the rows by the same text).
     */
    public function uuidFormat(): string
    {
        if ($this->uuidFormat !== 'auto') {
            return $this->uuidFormat;
        }

        return $this->resolvedFormat ??= $this->detectFormat();
    }

    private function detectFormat(): string
    {
        $row = User::query()->whereNotNull('game_id')
            ->selectRaw('SUM(CASE WHEN LENGTH(game_id) = 32 THEN 1 ELSE 0 END) AS dashless, COUNT(*) AS total')->first();

        $total = (int) ($row->total ?? 0);

        // no players yet: Azuriom's Minecraft games store the id without dashes
        return $total === 0 || (int) $row->dashless * 2 >= $total ? 'dashless' : 'dashed';
    }

    /**
     * Re-keys canonical (dashed) uuids to the spelling of the table. Rows a plugin version wrote earlier in the
     * other spelling are cleaned up too: the other spelling is added with an empty wish list.
     *
     * @param  array<string, string[]>  $desired
     * @return array<string, string[]>
     */
    private function forTable(array $desired): array
    {
        $format = $this->uuidFormat();
        $other = $format === 'dashless' ? 'dashed' : 'dashless';
        $result = [];

        foreach ($desired as $uuid => $permissions) {
            $result[PermissionFormat::tableUuid($uuid, $format)] = $permissions;
        }

        foreach ($desired as $uuid => $permissions) {
            $result[PermissionFormat::tableUuid($uuid, $other)] ??= [];
        }

        return $result;
    }

    /**
     * Brings user_permissions in line with the rules for every player.
     *
     * @return array{players:int, insert:int, delete:int, warnings:array<int, array{type:string, detail:string}>}
     */
    public function syncAll(bool $dryRun = false): array
    {
        $rules = $this->rules();
        $public = $this->publicProfiles();
        $desired = [];
        $warnings = [];

        User::query()->select(['id', 'name', 'game_id', 'role_id'])->orderBy('id')->chunk(1000, function ($users) use (&$desired, $rules, $public, &$warnings) {
            $desired += DesiredBuilder::build($users->map->only(['id', 'name', 'game_id', 'role_id'])->all(), $rules, $this->maxLength, $warnings, $public);
        });

        $desired = $this->forTable($desired);

        // players that vanished (deleted user, changed game_id) still have rows in the ledger
        foreach (LedgerRow::query()->distinct()->pluck('player_uuid') as $uuid) {
            $desired[$uuid] ??= [];
        }

        return $this->apply($desired, $dryRun) + ['warnings' => $warnings];
    }

    /**
     * @param  string|null  $oldGameId  the previous game_id when it changed: its rows are removed
     */
    public function syncUser(User $user, ?string $oldGameId = null): array
    {
        $warnings = [];
        $desired = DesiredBuilder::build(
            [['id' => $user->id, 'name' => $user->name, 'game_id' => $user->game_id, 'role_id' => $user->role_id]],
            $this->rules(), $this->maxLength, $warnings, $this->publicProfiles()
        );

        $old = PermissionFormat::uuid($oldGameId);

        if ($old !== null && ! isset($desired[$old])) {
            $desired[$old] = [];
        }

        return $this->apply($this->forTable($desired)) + ['warnings' => $warnings];
    }

    public function syncRole(int $roleId): array
    {
        $rules = $this->rules();
        $public = $this->publicProfiles();
        $desired = [];
        $warnings = [];

        User::query()->where('role_id', $roleId)->select(['id', 'name', 'game_id', 'role_id'])->orderBy('id')->chunk(1000, function ($users) use (&$desired, $rules, $public, &$warnings) {
            $desired += DesiredBuilder::build($users->map->only(['id', 'name', 'game_id', 'role_id'])->all(), $rules, $this->maxLength, $warnings, $public);
        });

        return $this->apply($this->forTable($desired)) + ['warnings' => $warnings];
    }

    /**
     * Removes everything the plugin wrote for a player that is gone.
     */
    public function removePlayer(?string $gameId): void
    {
        $uuid = PermissionFormat::uuid($gameId);

        if ($uuid !== null) {
            $this->apply($this->forTable([$uuid => []]));
        }
    }

    /**
     * @return array<string, bool>
     */
    private function publicProfiles(): array
    {
        return Profile::query()->where('limited', false)->pluck('uuid')->mapWithKeys(fn ($uuid) => [$uuid => true])->all();
    }

    /**
     * @return array<int, array<string, mixed>>
     */
    private function rules(): array
    {
        return Rule::query()
            ->get(['subject_type', 'subject_id', 'resource_type', 'profile_uuid', 'mod_key'])
            ->map(fn (Rule $rule) => [
                'subject_type' => $rule->subject_type,
                'subject_id' => (int) $rule->subject_id,
                'resource_type' => $rule->resource_type,
                'profile_uuid' => $rule->profile_uuid,
                'mod_key' => $rule->mod_key === '' ? null : $rule->mod_key,
            ])->all();
    }

    /**
     * @param  array<string, string[]>  $desired  player uuid => permissions
     * @return array{players:int, insert:int, delete:int}
     */
    private function apply(array $desired, bool $dryRun = false): array
    {
        $db = DB::connection(self::CONNECTION);
        $inserted = 0;
        $deleted = 0;

        foreach (array_chunk($desired, self::CHUNK, true) as $chunk) {
            $uuids = array_keys($chunk);

            $current = [];

            foreach ($db->table($this->table)->whereIn($this->uuidColumn, $uuids)->get([$this->uuidColumn, $this->permissionColumn]) as $row) {
                $current[strtolower($row->{$this->uuidColumn})][] = $row->{$this->permissionColumn};
            }

            $ledger = [];

            foreach (LedgerRow::query()->whereIn('player_uuid', $uuids)->get(['player_uuid', 'permission']) as $row) {
                $ledger[$row->player_uuid][] = $row->permission;
            }

            $inserts = [];
            $ledgerAdd = [];
            $ledgerRemove = [];
            $deletes = [];

            foreach ($chunk as $uuid => $wanted) {
                $plan = SyncPlanner::plan($wanted, $current[$uuid] ?? [], $ledger[$uuid] ?? []);

                foreach ($plan['insert'] as $permission) {
                    $inserts[] = [$this->uuidColumn => $uuid, $this->permissionColumn => $permission];
                }

                foreach ($plan['delete'] as $permission) {
                    $deletes[$uuid][] = $permission;
                }

                foreach (array_diff($plan['ledger'], $ledger[$uuid] ?? []) as $permission) {
                    $ledgerAdd[] = ['player_uuid' => $uuid, 'permission' => $permission];
                }

                foreach (array_diff($ledger[$uuid] ?? [], $plan['ledger']) as $permission) {
                    $ledgerRemove[$uuid][] = $permission;
                }
            }

            $inserted += count($inserts);
            $deleted += array_sum(array_map('count', $deletes));

            if ($dryRun) {
                continue;
            }

            // user_permissions first, then the ledger: a repeated run repairs any gap in between
            $db->transaction(function () use ($db, $inserts, $deletes) {
                foreach (array_chunk($inserts, 500) as $rows) {
                    $db->table($this->table)->insert($rows);
                }

                foreach ($deletes as $uuid => $permissions) {
                    $db->table($this->table)->where($this->uuidColumn, $uuid)
                        ->whereIn($this->permissionColumn, $permissions)->delete();
                }
            });

            DB::transaction(function () use ($ledgerAdd, $ledgerRemove) {
                foreach ($ledgerRemove as $uuid => $permissions) {
                    LedgerRow::query()->where('player_uuid', $uuid)->whereIn('permission', $permissions)->delete();
                }

                foreach (array_chunk($ledgerAdd, 500) as $rows) {
                    LedgerRow::query()->insertOrIgnore($rows);
                }
            });
        }

        if (! $dryRun && ($inserted || $deleted)) {
            Log::info("LauncherAccess: $inserted permission rows added, $deleted removed");
        }

        return ['players' => count($desired), 'insert' => $inserted, 'delete' => $deleted];
    }
}
