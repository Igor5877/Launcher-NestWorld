<?php

use Azuriom\Plugin\LauncherAccess\Controllers\Admin\AccessController;
use Azuriom\Plugin\LauncherAccess\Controllers\Admin\OverviewController;
use Azuriom\Plugin\LauncherAccess\Controllers\Admin\SettingController;
use Illuminate\Support\Facades\Route;

/*
|--------------------------------------------------------------------------
| Admin Routes
|--------------------------------------------------------------------------
|
| Loaded by the RouteServiceProvider within the "admin-access" group
| (web, auth, admin.access): every route also needs the plugin permission.
|
*/

Route::middleware('can:launcheraccess.manage')->group(function () {
    Route::get('/', [AccessController::class, 'index'])->name('access');
    Route::get('/subjects', [AccessController::class, 'subjects'])->name('subjects');
    Route::post('/', [AccessController::class, 'save'])->name('access.save');

    Route::get('/overview', [OverviewController::class, 'index'])->name('overview');

    Route::get('/settings', [SettingController::class, 'show'])->name('settings');
    Route::post('/settings', [SettingController::class, 'save'])->name('settings.save');
    Route::post('/settings/import', [SettingController::class, 'import'])->name('settings.import');
    Route::post('/settings/sync', [SettingController::class, 'sync'])->name('settings.sync');
});
