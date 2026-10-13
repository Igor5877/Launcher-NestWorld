<?php

/*
 * Fills a throw-away Azuriom with example players, roles, profiles, mods and rules, and creates a user_permissions
 * table shaped like the one LaunchServer uses:   php tests/demo-seed.php /path/to/azuriom
 */

$root = $argv[1] ?? null;

if (! $root || ! is_file($root.'/artisan')) {
    fwrite(STDERR, "usage: php tests/demo-seed.php /path/to/azuriom\n");
    exit(2);
}

chdir($root);
require $root.'/vendor/autoload.php';
$app = require $root.'/bootstrap/app.php';
$app->make(Illuminate\Contracts\Console\Kernel::class)->bootstrap();

use Azuriom\Models\Role;
use Azuriom\Models\User;
use Azuriom\Plugin\LauncherAccess\Models\LedgerRow;
use Azuriom\Plugin\LauncherAccess\Models\Mod;
use Azuriom\Plugin\LauncherAccess\Models\Profile;
use Azuriom\Plugin\LauncherAccess\Models\Rule;
use Azuriom\Plugin\LauncherAccess\Services\AccessSyncer;
use Azuriom\Plugin\LauncherAccess\Support\CatalogParser;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Schema;

foreach ([LedgerRow::class, Rule::class, Mod::class, Profile::class] as $model) {
    $model::query()->delete();
}

DB::table('settings')->where('name', 'like', 'launcheraccess.%')->delete();

$connection = DB::connection(AccessSyncer::CONNECTION);
Schema::connection(AccessSyncer::CONNECTION)->dropIfExists('user_permissions');
Schema::connection(AccessSyncer::CONNECTION)->create('user_permissions', function ($table) use ($connection) {
    $table->increments('id');
    $uuid = $table->string('uuid', 100)->index();
    $name = $table->string('name', 100);

    if ($connection->getDriverName() === 'mysql') {
        // the same collation as the real table
        $uuid->collation('utf8mb4_0900_ai_ci');
        $name->collation('utf8mb4_0900_ai_ci');
    }
});

function offlineGameId(string $name): string
{
    // what Azuriom's MinecraftOfflineGame stores: md5("OfflinePlayer:name") as a v3 uuid without dashes
    $hash = md5('OfflinePlayer:'.$name, true);
    $hash[6] = chr((ord($hash[6]) & 0x0f) | 0x30);
    $hash[8] = chr((ord($hash[8]) & 0x3f) | 0x80);

    return bin2hex($hash);
}

function demoRole(string $name, int $power, bool $admin = false): Role
{
    return Role::query()->firstOrCreate(['name' => $name], ['color' => '#3b82f6', 'power' => $power, 'is_admin' => $admin]);
}

function demoUser(string $name, ?string $gameId, Role $role, string $password = 'pass1234'): User
{
    return User::query()->where('name', $name)->first() ?? User::forceCreate([
        'name' => $name, 'email' => strtolower($name).'@example.com', 'password' => bcrypt($password),
        'game_id' => $gameId, 'role_id' => $role->id, 'email_verified_at' => now(),
    ]);
}

$adminRole = demoRole('Admin', 100, true);
$vip = demoRole('VIP', 20);
$moderator = demoRole('Moderator', 30);
$member = Role::query()->find(Role::defaultRoleId()) ?? demoRole('Member', 1);

demoUser('admin', offlineGameId('admin'), $adminRole, 'admin123');

$users = [];

foreach (['Igor' => $vip, 'Steve' => $member, 'Alex' => $vip, 'Notch' => $moderator, 'Herobrine' => $member, 'Newbie' => $member] as $name => $role) {
    $users[$name] = demoUser($name, $name === 'Newbie' ? null : offlineGameId($name), $role);
}

$catalog = CatalogParser::parse(json_encode([
    ['uuid' => '5a1f2b0c-1111-4a3c-9d2e-000000000001', 'title' => 'Sky-Technologies-build', 'version' => '1.20.1', 'limited' => false, 'sortIndex' => 1,
        'optionals' => [['name' => 'Sodium'], ['name' => 'Iris Shaders', 'limited' => true], ['name' => 'Minimap']]],
    ['uuid' => '5a1f2b0c-2222-4a3c-9d2e-000000000002', 'title' => 'NestWorld-Magic', 'version' => '1.20.1', 'limited' => true, 'sortIndex' => 2,
        'optionals' => [['name' => 'Botania', 'limited' => true], ['name' => 'Ars Nouveau', 'limited' => true], ['name' => 'JEI']]],
    ['uuid' => '5a1f2b0c-3333-4a3c-9d2e-000000000003', 'title' => 'Test-Build (staff)', 'version' => '1.21', 'limited' => true, 'sortIndex' => 3,
        'optionals' => [['name' => 'Debug Tools', 'limited' => true]]],
]));

foreach ($catalog['profiles'] as $profile) {
    Profile::query()->updateOrCreate(['uuid' => $profile['uuid']], $profile + ['synced_at' => now()]);
}

foreach ($catalog['mods'] as $mod) {
    Mod::query()->updateOrCreate(['profile_uuid' => $mod['profile_uuid'], 'mod_key' => $mod['mod_key']], $mod);
}

[$sky, $magic, $staff] = array_column($catalog['profiles'], 'uuid');

$rule = fn (string $type, int $id, string $resource, string $profile, string $mod = '') => Rule::query()->create([
    'subject_type' => $type, 'subject_id' => $id, 'resource_type' => $resource, 'profile_uuid' => $profile, 'mod_key' => $mod,
]);

$rule('role', $vip->id, 'profile', $magic);                 // everybody with the VIP role: NestWorld-Magic
$rule('role', $vip->id, 'mod', $magic, 'botania');
$rule('role', $moderator->id, 'profile', $staff);           // moderators: the staff build
$rule('role', $moderator->id, 'mod', $staff, 'debug tools');
$rule('user', $users['Igor']->id, 'profile', $staff);       // Igor additionally: the staff build
$rule('user', $users['Igor']->id, 'mod', $magic, 'ars nouveau');
$rule('user', $users['Steve']->id, 'profile', $magic);      // Steve: only NestWorld-Magic
$rule('user', $users['Newbie']->id, 'profile', $magic);     // has no game id yet: written after the first login

// a hand written row: the plugin must never touch it
$gameId = offlineGameId('Steve');
$connection->table('user_permissions')->insert([
    'uuid' => substr($gameId, 0, 8).'-'.substr($gameId, 8, 4).'-'.substr($gameId, 12, 4).'-'.substr($gameId, 16, 4).'-'.substr($gameId, 20),
    'name' => 'launcher.debug.manual',
]);

$report = AccessSyncer::fromSettings()->syncAll();

echo "Seeded: {$report['players']} players, {$report['insert']} permission rows written\n";
echo 'user_permissions now has '.$connection->table('user_permissions')->count()." rows\n";
