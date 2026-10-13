<?php

namespace Azuriom\Plugin\LauncherAccess\Providers;

use Azuriom\Extensions\Plugin\BasePluginServiceProvider;
use Azuriom\Models\ActionLog;
use Azuriom\Models\Permission;
use Azuriom\Models\Role;
use Azuriom\Models\User;
use Azuriom\Plugin\LauncherAccess\Commands\SyncCommand;
use Azuriom\Plugin\LauncherAccess\Observers\RoleObserver;
use Azuriom\Plugin\LauncherAccess\Observers\UserObserver;
use Azuriom\Plugin\LauncherAccess\Services\AccessSyncer;
use Illuminate\Console\Scheduling\Schedule;

class LauncherAccessServiceProvider extends BasePluginServiceProvider
{
    /**
     * Register any plugin services.
     */
    public function register(): void
    {
        //
    }

    /**
     * Bootstrap any plugin services.
     */
    public function boot(): void
    {
        $this->loadViews();

        $this->loadTranslations();

        $this->loadMigrations();

        $this->registerAdminNavigation();

        // user_permissions is not an Azuriom table: use a copy of the default connection without the table prefix
        $default = config('database.default');
        $connection = config("database.connections.$default");

        if (is_array($connection) && ! config('database.connections.'.AccessSyncer::CONNECTION)) {
            config(['database.connections.'.AccessSyncer::CONNECTION => array_merge($connection, ['prefix' => ''])]);
        }

        Permission::registerPermissions([
            'launcheraccess.manage' => 'launcheraccess::admin.permission',
        ]);

        ActionLog::registerLogs([
            'launcheraccess.rules.updated' => [
                'icon' => 'shield-lock',
                'color' => 'info',
                'message' => 'launcheraccess::admin.logs.rules',
            ],
            'launcheraccess.settings.updated' => [
                'icon' => 'gear',
                'color' => 'info',
                'message' => 'launcheraccess::admin.logs.settings',
            ],
            'launcheraccess.catalog.imported' => [
                'icon' => 'cloud-download',
                'color' => 'info',
                'message' => 'launcheraccess::admin.logs.import',
            ],
            'launcheraccess.synced' => [
                'icon' => 'arrow-repeat',
                'color' => 'info',
                'message' => 'launcheraccess::admin.logs.synced',
            ],
        ]);

        // Keep user_permissions right when a player changes role, gets a game id or is deleted
        User::observe(UserObserver::class);
        Role::observe(RoleObserver::class);

        $this->registerSchedule();

        $this->commands([SyncCommand::class]);
    }

    /**
     * Safety net: a full reconciliation also catches changes that bypass the model events.
     */
    protected function schedule(Schedule $schedule): void
    {
        $schedule->command('launcheraccess:sync')->hourly();
    }

    /**
     * Return the admin navigations routes to register in the dashboard.
     *
     * @return array<string, array<string, string>>
     */
    protected function adminNavigation(): array
    {
        return [
            'launcheraccess' => [
                'name' => trans('launcheraccess::admin.nav.title'),
                'type' => 'dropdown',
                'icon' => 'bi bi-shield-lock',
                'route' => 'launcheraccess.admin.*',
                'permission' => 'launcheraccess.manage',
                'items' => [
                    'launcheraccess.admin.access' => trans('launcheraccess::admin.nav.access'),
                    'launcheraccess.admin.overview' => trans('launcheraccess::admin.nav.overview'),
                    'launcheraccess.admin.settings' => trans('launcheraccess::admin.nav.settings'),
                ],
            ],
        ];
    }
}
