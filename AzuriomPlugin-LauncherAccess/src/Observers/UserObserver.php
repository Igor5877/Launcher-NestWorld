<?php

namespace Azuriom\Plugin\LauncherAccess\Observers;

use Azuriom\Models\User;
use Azuriom\Plugin\LauncherAccess\Services\AccessSyncer;
use Illuminate\Support\Facades\Log;

class UserObserver
{
    public function updated(User $user): void
    {
        if (! $user->wasChanged(['role_id', 'game_id'])) {
            return;
        }

        $oldGameId = $user->wasChanged('game_id') ? $user->getOriginal('game_id') : null;

        $this->safely(fn () => AccessSyncer::fromSettings()->syncUser($user, $oldGameId));
    }

    public function deleted(User $user): void
    {
        $this->safely(fn () => AccessSyncer::fromSettings()->removePlayer($user->game_id));
    }

    /**
     * A problem with user_permissions must never stop a player from being saved on the site.
     */
    private function safely(callable $action): void
    {
        if (! setting('launcheraccess.auto_sync', true)) {
            return;
        }

        try {
            $action();
        } catch (\Throwable $e) {
            Log::error('LauncherAccess: sync failed: '.$e->getMessage());
        }
    }
}
