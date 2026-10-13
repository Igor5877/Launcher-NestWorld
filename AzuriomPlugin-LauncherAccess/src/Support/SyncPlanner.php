<?php

namespace Azuriom\Plugin\LauncherAccess\Support;

/**
 * Decides what to insert into and delete from user_permissions for one player.
 *
 * The plugin owns only the rows it inserted itself (the "ledger"): rows added by hand are never touched,
 * and a desired row that already exists is left alone (and does not become the plugin's).
 */
final class SyncPlanner
{
    /**
     * @param  string[]  $desired  permissions the player must have
     * @param  string[]  $current  permissions the player has in user_permissions now
     * @param  string[]  $ledger   permissions the plugin inserted earlier
     * @return array{insert: string[], delete: string[], ledger: string[]}
     */
    public static function plan(array $desired, array $current, array $ledger): array
    {
        $desired = array_values(array_unique($desired));
        $current = array_flip($current);
        $desiredSet = array_flip($desired);
        $ledger = array_values(array_unique($ledger));

        $insert = [];

        foreach ($desired as $permission) {
            if (! isset($current[$permission])) {
                $insert[] = $permission;
            }
        }

        $delete = [];
        $keep = [];

        foreach ($ledger as $permission) {
            if (isset($desiredSet[$permission])) {
                $keep[] = $permission;
            } elseif (isset($current[$permission]) && PermissionFormat::isManaged($permission)) {
                $delete[] = $permission;
            }
            // a ledger row that is neither wanted nor present any more is simply forgotten
        }

        $newLedger = array_values(array_unique(array_merge($keep, $insert)));

        sort($insert);
        sort($delete);
        sort($newLedger);

        return ['insert' => $insert, 'delete' => $delete, 'ledger' => $newLedger];
    }
}
