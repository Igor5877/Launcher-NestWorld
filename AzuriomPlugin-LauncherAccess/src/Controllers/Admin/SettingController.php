<?php

namespace Azuriom\Plugin\LauncherAccess\Controllers\Admin;

use Azuriom\Http\Controllers\Controller;
use Azuriom\Models\ActionLog;
use Azuriom\Models\Setting;
use Azuriom\Plugin\LauncherAccess\Models\LedgerRow;
use Azuriom\Plugin\LauncherAccess\Models\Mod;
use Azuriom\Plugin\LauncherAccess\Models\Profile;
use Azuriom\Plugin\LauncherAccess\Models\Rule;
use Azuriom\Plugin\LauncherAccess\Services\AccessSyncer;
use Azuriom\Plugin\LauncherAccess\Support\CatalogParser;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Log;
use Illuminate\Support\Facades\Schema;

class SettingController extends Controller
{
    public function show()
    {
        return view('launcheraccess::admin.settings', [
            'table' => setting('launcheraccess.table', 'user_permissions'),
            'uuidColumn' => setting('launcheraccess.uuid_column', 'uuid'),
            'permissionColumn' => setting('launcheraccess.permission_column', 'name'),
            'maxLength' => (int) setting('launcheraccess.max_length', 100),
            'autoSync' => (bool) setting('launcheraccess.auto_sync', true),
            'uuidFormat' => (string) setting('launcheraccess.uuid_format', 'auto'),
            'detectedFormat' => AccessSyncer::fromSettings()->uuidFormat(),
            'profilesCount' => Profile::query()->count(),
            'modsCount' => Mod::query()->count(),
            'rulesCount' => Rule::query()->count(),
            'managedCount' => LedgerRow::query()->count(),
            'syncedAt' => Profile::query()->max('synced_at'),
        ]);
    }

    public function save(Request $request)
    {
        $data = $request->validate([
            'table' => ['required', 'regex:/^[A-Za-z0-9_]{1,64}$/D'],
            'uuid_column' => ['required', 'regex:/^[A-Za-z0-9_]{1,64}$/D'],
            'permission_column' => ['required', 'regex:/^[A-Za-z0-9_]{1,64}$/D'],
            'max_length' => ['required', 'integer', 'min:40', 'max:255'],
            'uuid_format' => ['nullable', 'in:auto,dashless,dashed'],
        ]);

        // the table must exist and have both columns, otherwise nothing can be written
        $schema = Schema::connection(AccessSyncer::CONNECTION);

        if (! $schema->hasTable($data['table'])
            || ! $schema->hasColumns($data['table'], [$data['uuid_column'], $data['permission_column']])) {
            return back()->withInput()->with('error', trans('launcheraccess::admin.settings.table_missing'));
        }

        Setting::updateSettings([
            'launcheraccess.table' => $data['table'],
            'launcheraccess.uuid_column' => $data['uuid_column'],
            'launcheraccess.permission_column' => $data['permission_column'],
            'launcheraccess.max_length' => $data['max_length'],
            'launcheraccess.uuid_format' => $data['uuid_format'] ?? 'auto',
            'launcheraccess.auto_sync' => $request->has('auto_sync') ? '1' : '0',
        ]);

        ActionLog::log('launcheraccess.settings.updated');

        return to_route('launcheraccess.admin.settings')->with('success', trans('messages.status.success'));
    }

    /**
     * Fallback to the LaunchServer exporter: paste the profile JSON files.
     */
    public function import(Request $request)
    {
        $data = $request->validate(['catalog' => ['required', 'string', 'max:2000000']]);

        $parsed = CatalogParser::parse($data['catalog']);

        if ($parsed['profiles'] === []) {
            return back()->withInput()->with('error', trans('launcheraccess::admin.settings.import_empty'));
        }

        DB::transaction(function () use ($parsed) {
            $now = now();

            foreach ($parsed['profiles'] as $profile) {
                Profile::query()->updateOrCreate(['uuid' => $profile['uuid']], $profile + ['synced_at' => $now]);
            }

            foreach ($parsed['mods'] as $mod) {
                Mod::query()->updateOrCreate(['profile_uuid' => $mod['profile_uuid'], 'mod_key' => $mod['mod_key']], $mod);
            }
        });

        ActionLog::log('launcheraccess.catalog.imported', null, [
            'profiles' => count($parsed['profiles']), 'mods' => count($parsed['mods']),
        ]);

        $message = trans('launcheraccess::admin.settings.imported', [
            'profiles' => count($parsed['profiles']), 'mods' => count($parsed['mods']),
        ]);

        return to_route('launcheraccess.admin.settings')->with('success', $message)
            ->with('import_errors', array_slice($parsed['errors'], 0, 20));
    }

    /**
     * Preview or run a full reconciliation of user_permissions.
     */
    public function sync(Request $request)
    {
        $dryRun = $request->input('mode') !== 'apply';

        try {
            $report = AccessSyncer::fromSettings()->syncAll($dryRun);
        } catch (\Throwable $e) {
            Log::error('LauncherAccess: full sync failed: '.$e->getMessage());

            return to_route('launcheraccess.admin.settings')->with('error', trans('launcheraccess::admin.access.sync_failed'));
        }

        if (! $dryRun) {
            ActionLog::log('launcheraccess.synced', null, [
                'players' => $report['players'], 'insert' => $report['insert'], 'delete' => $report['delete'],
            ]);
        }

        return to_route('launcheraccess.admin.settings')
            ->with('success', trans($dryRun ? 'launcheraccess::admin.settings.preview' : 'launcheraccess::admin.settings.synced', [
                'players' => $report['players'], 'insert' => $report['insert'], 'delete' => $report['delete'],
            ]))
            ->with('sync_warnings', array_slice($report['warnings'], 0, 30));
    }
}
