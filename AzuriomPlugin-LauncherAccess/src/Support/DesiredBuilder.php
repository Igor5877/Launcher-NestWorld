<?php

namespace Azuriom\Plugin\LauncherAccess\Support;

/**
 * Turns the rules chosen in the admin panel into the permission rows every player must have.
 */
final class DesiredBuilder
{
    /**
     * @param  array<int, array{id:int, game_id:?string, role_id:?int}>  $users
     * @param  array<int, array{subject_type:string, subject_id:int, resource_type:string, profile_uuid:string, mod_key:?string}>  $rules
     * @param  array<int, array{type:string, detail:string}>  $warnings  filled with problems worth showing to the admin
     * @return array<string, string[]> player uuid => permissions. Players with no rules are present with an empty
     *                                 list, so rows the plugin wrote earlier get removed
     */
    public static function build(array $users, array $rules, int $maxLength, array &$warnings = []): array
    {
        $byUser = [];
        $byRole = [];

        foreach ($rules as $rule) {
            $profile = PermissionFormat::uuid($rule['profile_uuid'] ?? null);

            if ($profile === null) {
                $warnings[] = ['type' => 'bad_profile', 'detail' => (string) ($rule['profile_uuid'] ?? '')];

                continue;
            }

            if ($rule['resource_type'] === 'profile') {
                $entry = ['profile' => $profile, 'mod' => null];
            } elseif ($rule['resource_type'] === 'mod') {
                $key = PermissionFormat::modKey($rule['mod_key'] ?? null);

                if ($key === null) {
                    $warnings[] = ['type' => 'bad_mod', 'detail' => $profile.'/'.($rule['mod_key'] ?? '')];

                    continue;
                }

                $entry = ['profile' => $profile, 'mod' => $key];
            } else {
                continue;
            }

            if ($rule['subject_type'] === 'user') {
                $byUser[(int) $rule['subject_id']][] = $entry;
            } elseif ($rule['subject_type'] === 'role') {
                $byRole[(int) $rule['subject_id']][] = $entry;
            }
        }

        $desired = [];

        foreach ($users as $user) {
            $uuid = PermissionFormat::uuid($user['game_id'] ?? null);

            if ($uuid === null) {
                if (! empty($byUser[$user['id']]) || (! empty($user['role_id']) && ! empty($byRole[$user['role_id']]))) {
                    $warnings[] = ['type' => 'no_game_id', 'detail' => (string) $user['id']];
                }

                continue;
            }

            $entries = array_merge($byUser[$user['id']] ?? [], $byRole[$user['role_id'] ?? 0] ?? []);
            $permissions = [];
            $profiles = [];

            foreach ($entries as $entry) {
                if ($entry['mod'] === null) {
                    $profiles[$entry['profile']] = true;

                    foreach (PermissionFormat::profile($entry['profile']) as $permission) {
                        $permissions[$permission] = true;
                    }
                }
            }

            foreach ($entries as $entry) {
                if ($entry['mod'] !== null) {
                    $permissions[PermissionFormat::modShow($entry['profile'], $entry['mod'])] = true;

                    if (! isset($profiles[$entry['profile']])) {
                        $warnings[] = ['type' => 'mod_without_profile', 'detail' => $user['id'].' '.$entry['profile']];
                    }
                }
            }

            $list = [];

            foreach (array_keys($permissions) as $permission) {
                if (PermissionFormat::fits($permission, $maxLength)) {
                    $list[] = $permission;
                } else {
                    $warnings[] = ['type' => 'too_long', 'detail' => $permission];
                }
            }

            sort($list);
            $desired[$uuid] = $list;
        }

        return $desired;
    }
}
