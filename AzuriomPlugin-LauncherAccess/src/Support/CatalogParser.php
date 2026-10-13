<?php

namespace Azuriom\Plugin\LauncherAccess\Support;

/**
 * Reads the list of profiles and mods (the JSON the LaunchServer exporter writes or an admin pastes)
 * into rows for the catalog tables. Nothing in the JSON is trusted.
 */
final class CatalogParser
{
    /**
     * @return array{profiles: array<int, array<string, mixed>>, mods: array<int, array<string, mixed>>, errors: string[]}
     */
    public static function parse(string $json): array
    {
        $profiles = [];
        $mods = [];
        $errors = [];

        try {
            $data = json_decode($json, true, 16, JSON_THROW_ON_ERROR);
        } catch (\JsonException $e) {
            return ['profiles' => [], 'mods' => [], 'errors' => ['invalid_json']];
        }

        if (! is_array($data)) {
            return ['profiles' => [], 'mods' => [], 'errors' => ['invalid_json']];
        }

        // a single profile file (profiles/*.json of LaunchServer) or a list of them
        if (isset($data['uuid']) || isset($data['title'])) {
            $data = [$data];
        }

        foreach ($data as $index => $profile) {
            if (! is_array($profile)) {
                $errors[] = "profile #$index: not an object";

                continue;
            }

            $uuid = PermissionFormat::uuid($profile['uuid'] ?? null);
            $title = trim((string) ($profile['title'] ?? ''));

            if ($uuid === null || $title === '') {
                $errors[] = "profile #$index: uuid or title is missing";

                continue;
            }

            $profiles[$uuid] = [
                'uuid' => $uuid,
                'title' => mb_substr($title, 0, 255),
                'version' => isset($profile['version']) ? mb_substr((string) (is_array($profile['version']) ? ($profile['version']['name'] ?? '') : $profile['version']), 0, 64) : null,
                'limited' => (bool) ($profile['limited'] ?? false),
                'sort_index' => (int) ($profile['sortIndex'] ?? $profile['sort_index'] ?? 0),
            ];

            // LaunchServer files call the list "updateOptional", the exporter "optionals"
            $optionals = $profile['optionals'] ?? $profile['updateOptional'] ?? [];

            foreach (is_array($optionals) ? $optionals : [] as $optional) {
                $name = is_array($optional) ? ($optional['name'] ?? null) : null;
                $key = PermissionFormat::modKey(is_string($name) ? $name : null);

                if ($key === null) {
                    $errors[] = "profile $uuid: mod without a usable name";

                    continue;
                }

                // two files with the same name in one profile: the stricter flag wins (hidden stays hidden)
                $duplicateLimited = isset($mods[$uuid.'|'.$key]) && $mods[$uuid.'|'.$key]['limited'];

                $mods[$uuid.'|'.$key] = [
                    'profile_uuid' => $uuid,
                    'mod_key' => mb_substr($key, 0, 100),
                    'name' => mb_substr($name, 0, 255),
                    'category' => isset($optional['category']) ? mb_substr((string) $optional['category'], 0, 100) : null,
                    'info' => isset($optional['info']) ? mb_substr((string) $optional['info'], 0, 1000) : null,
                    'limited' => $duplicateLimited || (bool) ($optional['limited'] ?? false),
                    'visible' => (bool) ($optional['visible'] ?? true),
                ];
            }
        }

        return ['profiles' => array_values($profiles), 'mods' => array_values($mods), 'errors' => $errors];
    }
}
