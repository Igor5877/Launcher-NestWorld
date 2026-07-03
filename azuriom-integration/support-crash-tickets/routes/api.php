<?php

use Azuriom\Plugin\Support\Controllers\Api\CrashReportController;
use Illuminate\Support\Facades\Route;

/*
|--------------------------------------------------------------------------
| API Routes (LaunchServer -> Support)
|--------------------------------------------------------------------------
|
| Завантажується патченим RouteServiceProvider з middleware "api"
| (без сесії та CSRF). Повний URL: POST /api/support/crash
|
*/

Route::post('/crash', [CrashReportController::class, 'store'])->name('crash.store');
