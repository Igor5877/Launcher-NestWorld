<?php

namespace Azuriom\Plugin\Launcher\Providers;

use Azuriom\Extensions\Plugin\BasePluginServiceProvider;

class LauncherServiceProvider extends BasePluginServiceProvider
{
    public function register(): void {}

    public function boot(): void
    {
        $this->loadViews();
        $this->loadMigrations();
        $this->loadAdminRoutes();

        $this->registerAdminNavigation('Launcher', 'bi bi-controller', 'launcher.admin.index');
    }
}
