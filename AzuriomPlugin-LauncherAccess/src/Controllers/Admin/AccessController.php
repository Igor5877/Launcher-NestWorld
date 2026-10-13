<?php

namespace Azuriom\Plugin\LauncherAccess\Controllers\Admin;

use Azuriom\Http\Controllers\Controller;
use Azuriom\Models\ActionLog;
use Azuriom\Models\Role;
use Azuriom\Models\User;
use Azuriom\Plugin\LauncherAccess\Models\Mod;
use Azuriom\Plugin\LauncherAccess\Models\Profile;
use Azuriom\Plugin\LauncherAccess\Models\Rule;
use Azuriom\Plugin\LauncherAccess\Services\AccessSyncer;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Log;

class AccessController extends Controller
{
    /**
     * Pick a player or a role, tick the profiles and mods they may use.
     */
    public function index(Request $request)
    {
        $profiles = Profile::query()->orderBy('sort_index')->orderBy('title')->get();
        $mods = Mod::query()->orderBy('name')->get()->groupBy('profile_uuid');

        $subject = $this->subject((string) $request->query('subject', ''));
        $selected = ['profiles' => [], 'mods' => []];
        $inherited = ['role' => null, 'profiles' => [], 'mods' => []];

        if ($subject !== null) {
            $selected = $this->selection($subject['type'], $subject['model']->id);

            if ($subject['type'] === 'user' && $subject['model']->role_id) {
                $inherited = ['role' => $subject['model']->role?->name] + $this->selection('role', $subject['model']->role_id);
            }
        }

        return view('launcheraccess::admin.access', [
            'profiles' => $profiles,
            'mods' => $mods,
            'subject' => $subject,
            'selected' => $selected,
            'inherited' => $inherited,
            'warning' => $subject !== null && $subject['type'] === 'user' && ! $subject['model']->game_id,
        ]);
    }

    /**
     * Search for the drop-down: players by name and roles.
     */
    public function subjects(Request $request): JsonResponse
    {
        // "!" is the escape character (declared in the query): works the same on MySQL, PostgreSQL and SQLite
        $query = preg_replace('/([%_!])/', '!$1', trim((string) $request->query('q', '')));
        $query = '%'.$query.'%';

        $roles = Role::query()->whereRaw("name LIKE ? ESCAPE '!'", [$query])->orderBy('power', 'desc')->limit(10)->get(['id', 'name']);
        $users = User::query()->whereRaw("name LIKE ? ESCAPE '!'", [$query])->orderBy('name')->limit(30)->get(['id', 'name']);

        $items = [];

        foreach ($roles as $role) {
            $items[] = ['id' => 'role:'.$role->id, 'type' => 'role', 'label' => $role->name];
        }

        foreach ($users as $user) {
            $items[] = ['id' => 'user:'.$user->id, 'type' => 'user', 'label' => $user->name];
        }

        return response()->json($items);
    }

    public function save(Request $request)
    {
        $data = $request->validate([
            'subject' => ['required', 'regex:/^(user|role):\d+$/D'],
            'profiles' => ['nullable', 'array', 'max:500'],
            'profiles.*' => ['string', 'size:36'],
            'mods' => ['nullable', 'array', 'max:5000'],
            'mods.*' => ['string', 'max:150'],
        ]);

        $subject = $this->subject($data['subject']);

        abort_if($subject === null, 404);

        $type = $subject['type'];
        $id = $subject['model']->id;

        // only what the catalog knows can be granted
        $knownProfiles = Profile::query()->pluck('uuid')->all();
        $chosenProfiles = array_values(array_intersect($data['profiles'] ?? [], $knownProfiles));

        $knownMods = Mod::query()->get(['profile_uuid', 'mod_key'])
            ->mapWithKeys(fn (Mod $mod) => [$mod->profile_uuid.'|'.$mod->mod_key => [$mod->profile_uuid, $mod->mod_key]]);
        $chosenMods = [];

        foreach ($data['mods'] ?? [] as $value) {
            if (isset($knownMods[$value])) {
                $chosenMods[] = $knownMods[$value];
            }
        }

        $author = $request->user()?->id;

        DB::transaction(function () use ($type, $id, $author, $knownProfiles, $chosenProfiles, $chosenMods) {
            // rules for profiles that disappeared from LaunchServer are not shown in the form: keep them
            Rule::query()->where('subject_type', $type)->where('subject_id', $id)
                ->whereIn('profile_uuid', $knownProfiles)->delete();

            foreach ($chosenProfiles as $uuid) {
                Rule::query()->create([
                    'subject_type' => $type, 'subject_id' => $id, 'resource_type' => 'profile',
                    'profile_uuid' => $uuid, 'mod_key' => '', 'created_by' => $author,
                ]);
            }

            foreach ($chosenMods as [$uuid, $key]) {
                Rule::query()->create([
                    'subject_type' => $type, 'subject_id' => $id, 'resource_type' => 'mod',
                    'profile_uuid' => $uuid, 'mod_key' => $key, 'created_by' => $author,
                ]);
            }
        });

        ActionLog::log('launcheraccess.rules.updated', $subject['model'], [
            'subject' => $subject['label'], 'profiles' => count($chosenProfiles), 'mods' => count($chosenMods),
        ]);

        $redirect = to_route('launcheraccess.admin.access', ['subject' => $data['subject']]);

        if (! setting('launcheraccess.auto_sync', true)) {
            return $redirect->with('success', trans('launcheraccess::admin.access.saved_no_sync'));
        }

        try {
            $syncer = AccessSyncer::fromSettings();
            $type === 'user' ? $syncer->syncUser($subject['model']) : $syncer->syncRole($id);
        } catch (\Throwable $e) {
            Log::error('LauncherAccess: sync failed: '.$e->getMessage());

            return $redirect->with('error', trans('launcheraccess::admin.access.sync_failed'));
        }

        return $redirect->with('success', trans('launcheraccess::admin.access.saved'));
    }

    /**
     * @return array{type:string, model:User|Role, label:string}|null
     */
    private function subject(string $value): ?array
    {
        if (! preg_match('/^(user|role):(\d+)$/D', $value, $match)) {
            return null;
        }

        $model = $match[1] === 'user' ? User::query()->find((int) $match[2]) : Role::query()->find((int) $match[2]);

        return $model === null ? null : ['type' => $match[1], 'model' => $model, 'label' => $model->name];
    }

    /**
     * @return array{profiles: string[], mods: string[]}
     */
    private function selection(string $type, int $id): array
    {
        $rules = Rule::query()->where('subject_type', $type)->where('subject_id', $id)->get();

        return [
            'profiles' => $rules->where('resource_type', 'profile')->pluck('profile_uuid')->all(),
            'mods' => $rules->where('resource_type', 'mod')->map(fn (Rule $rule) => $rule->profile_uuid.'|'.$rule->mod_key)->values()->all(),
        ];
    }
}
