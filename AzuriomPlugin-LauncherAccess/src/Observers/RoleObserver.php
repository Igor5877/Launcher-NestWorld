<?php

namespace Azuriom\Plugin\LauncherAccess\Observers;

use Azuriom\Models\Role;
use Azuriom\Plugin\LauncherAccess\Models\Rule;
use Azuriom\Plugin\LauncherAccess\Services\AccessSyncer;
use Illuminate\Support\Facades\Log;

class RoleObserver
{
    public function deleted(Role $role): void
    {
        try {
            Rule::query()->where('subject_type', 'role')->where('subject_id', $role->id)->delete();

            if (setting('launcheraccess.auto_sync', true)) {
                // its members got another role in the meantime: recompute everybody
                AccessSyncer::fromSettings()->syncAll();
            }
        } catch (\Throwable $e) {
            Log::error('LauncherAccess: sync after a role was deleted failed: '.$e->getMessage());
        }
    }
}
