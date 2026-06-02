<?php

use Azuriom\Plugin\Launcher\Controllers\Admin\LauncherController;
use Illuminate\Support\Facades\Route;

Route::prefix('launcher')->name('launcher.admin.')->group(function () {
    Route::get('/', [LauncherController::class, 'index'])->name('index');
    Route::post('/profiles', [LauncherController::class, 'createProfile'])->name('profiles.create');
    Route::delete('/profiles/{uuid}', [LauncherController::class, 'deleteProfile'])->name('profiles.delete');
    Route::post('/access/role', [LauncherController::class, 'grantRole'])->name('access.role');
    Route::post('/access/player', [LauncherController::class, 'grantPlayer'])->name('access.player');
    Route::delete('/access/{id}', [LauncherController::class, 'revoke'])->name('access.revoke');
});
