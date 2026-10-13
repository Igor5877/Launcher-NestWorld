<?php

namespace Azuriom\Plugin\LauncherAccess\Support;

/**
 * The exact permission strings GravitLauncher reads from user_permissions.
 *
 * - launchserver.profile.<profile uuid>.show / .enter  (StdProtectHandler: list and select a "limited" profile)
 * - launcher.runtime.optionals.<profile uuid>.<mod name lower case>.show  (GUI launcher: show a "limited" optional mod)
 *
 * The plugin only ever writes or deletes strings that pass {@see isManaged()}: never "*" or anything else.
 */
final class PermissionFormat
{
    /** Length of the `name` column of user_permissions (varchar(100)). */
    public const DEFAULT_MAX_LENGTH = 100;

    private const UUID = '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}';

    /**
     * Normalizes a player or profile uuid to the form LaunchServer uses in the table:
     * lower case with dashes. Accepts the 32 digit form Azuriom stores in users.game_id.
     */
    public static function uuid(?string $value): ?string
    {
        if ($value === null) {
            return null;
        }

        $hex = strtolower(str_replace('-', '', trim($value)));

        if (! preg_match('/^[0-9a-f]{32}$/', $hex)) {
            return null;
        }

        return substr($hex, 0, 8).'-'.substr($hex, 8, 4).'-'.substr($hex, 12, 4).'-'
            .substr($hex, 16, 4).'-'.substr($hex, 20, 12);
    }

    /**
     * The part of the permission that names a mod: the optional file name in lower case,
     * exactly like TriggerManager does it. Null if the name cannot be put into a permission safely.
     */
    public static function modKey(?string $name): ?string
    {
        if ($name === null || $name === '' || preg_match('/[*\x00-\x1f\x7f]/', $name)) {
            return null;
        }

        return mb_strtolower($name, 'UTF-8');
    }

    public static function profileShow(string $profileUuid): string
    {
        return "launchserver.profile.$profileUuid.show";
    }

    public static function profileEnter(string $profileUuid): string
    {
        return "launchserver.profile.$profileUuid.enter";
    }

    public static function modShow(string $profileUuid, string $modKey): string
    {
        return "launcher.runtime.optionals.$profileUuid.$modKey.show";
    }

    /**
     * Both rows a profile needs: seeing it in the list and selecting it (which also allows downloading its files).
     *
     * @return string[]
     */
    public static function profile(string $profileUuid): array
    {
        return [self::profileShow($profileUuid), self::profileEnter($profileUuid)];
    }

    /**
     * Only strings of the two formats above may be written or removed by the plugin.
     */
    public static function isManaged(string $permission): bool
    {
        return (bool) preg_match(
            '/^(launchserver\.profile\.'.self::UUID.'\.(show|enter)'
            .'|launcher\.runtime\.optionals\.'.self::UUID.'\.[^*\x00-\x1f\x7f]+\.show)$/D', // D: "$" must not match before a trailing newline
            $permission
        );
    }

    public static function fits(string $permission, int $maxLength = self::DEFAULT_MAX_LENGTH): bool
    {
        return strlen($permission) <= $maxLength;
    }
}
