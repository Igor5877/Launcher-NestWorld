<?php

/*
 * Integration test against a real Azuriom checkout with the plugin enabled (SQLite is enough):
 *
 *   php tests/azuriom-integration.php /path/to/azuriom
 *
 * It resets the plugin tables and a user_permissions table shaped like the one LaunchServer uses
 * (id auto increment, uuid varchar(100), name varchar(100)), so run it on a throw-away installation only.
 */

$root = $argv[1] ?? null;

if (! $root || ! is_file($root.'/artisan')) {
    fwrite(STDERR, "usage: php tests/azuriom-integration.php /path/to/azuriom\n");
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
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Artisan;
use Illuminate\Support\Facades\Auth;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Schema;

$failed = 0;
$total = 0;

function check(string $name, bool $ok, string $detail = ''): void
{
    global $failed, $total;
    $total++;
    if (! $ok) {
        $failed++;
        echo "FAIL: $name $detail\n";
    }
}

function same(string $name, $expected, $actual): void
{
    check($name, $expected === $actual, "\n  expected: ".json_encode($expected)."\n  actual:   ".json_encode($actual));
}

const P1 = '00000000-0000-0000-0000-0000000000a1';
const P2 = '00000000-0000-0000-0000-0000000000a2';

function offlineGameId(string $name): string
{
    // what Azuriom's MinecraftOfflineGame stores: md5("OfflinePlayer:name") as a v3 uuid without dashes
    $hash = md5('OfflinePlayer:'.$name, true);
    $hash[6] = chr((ord($hash[6]) & 0x0f) | 0x30);
    $hash[8] = chr((ord($hash[8]) & 0x3f) | 0x80);

    return bin2hex($hash);
}

function dashed(string $gameId): string
{
    return substr($gameId, 0, 8).'-'.substr($gameId, 8, 4).'-'.substr($gameId, 12, 4).'-'.substr($gameId, 16, 4).'-'.substr($gameId, 20);
}

function perms(string $gameId): array
{
    $rows = DB::connection(AccessSyncer::CONNECTION)->table('user_permissions')->where('uuid', dashed($gameId))->pluck('name')->all();
    sort($rows);

    return $rows;
}

/** Removes the users of this test for good (Azuriom's own delete only anonymizes them). */
function cleanUsers(): void
{
    $ids = DB::table('users')->where(function ($query) {
        $query->whereIn('name', ['it_admin', 'it_steve', 'it_alex', 'it_nogame', 'it_bob'])->orWhere('name', 'like', 'Deleted #%');
    })->pluck('id')->all();

    foreach (['action_logs', 'comments', 'likes', 'user_ban', 'bans'] as $table) {
        if (Schema::hasTable($table) && Schema::hasColumn($table, 'user_id')) {
            DB::table($table)->whereIn('user_id', $ids)->delete();
        }
    }

    DB::table('users')->whereIn('id', $ids)->delete();
}

// ---- fresh state ----------------------------------------------------------------------------------------
Schema::connection(AccessSyncer::CONNECTION)->dropIfExists('user_permissions');
Schema::connection(AccessSyncer::CONNECTION)->create('user_permissions', function ($table) {
    $table->increments('id');
    $table->string('uuid', 100)->index();
    $table->string('name', 100);
});

foreach ([LedgerRow::class, Rule::class, Mod::class, Profile::class] as $model) {
    $model::query()->delete();
}

User::query()->whereIn('name', ['it_admin', 'it_steve', 'it_alex', 'it_nogame', 'it_bob'])->delete();
Role::query()->whereIn('name', ['it_admin_role', 'it_member', 'it_vip'])->delete();
DB::table('settings')->where('name', 'like', 'launcheraccess.%')->delete();

// deleting a user in Azuriom moves him to the default role, which a bare test database does not have yet
$defaultRoleId = Role::defaultRoleId();
$createdDefaultRole = false;

if (Role::query()->find($defaultRoleId) === null) {
    Role::forceCreate(['id' => $defaultRoleId, 'name' => 'it_default', 'color' => '#333333', 'power' => 0, 'is_admin' => false]);
    $createdDefaultRole = true;
}

$adminRole = Role::forceCreate(['name' => 'it_admin_role', 'color' => '#000000', 'power' => 100, 'is_admin' => true]);
$member = Role::forceCreate(['name' => 'it_member', 'color' => '#111111', 'power' => 1, 'is_admin' => false]);
$vip = Role::forceCreate(['name' => 'it_vip', 'color' => '#222222', 'power' => 2, 'is_admin' => false]);

function makeUser(string $name, ?string $gameId, Role $role): User
{
    return User::forceCreate([
        'name' => $name, 'email' => $name.'@example.com', 'password' => bcrypt('x'), 'game_id' => $gameId, 'role_id' => $role->id,
    ]);
}

$admin = makeUser('it_admin', offlineGameId('it_admin'), $adminRole);
$steve = makeUser('it_steve', offlineGameId('it_steve'), $member);
$alex = makeUser('it_alex', offlineGameId('it_alex'), $member);
$nogame = makeUser('it_nogame', null, $member);

// ---- a tiny HTTP client (cookies + CSRF) ---------------------------------------------------------------
$cookies = [];
$cookieOwner = null;

function http(string $method, string $uri, array $data = [], ?User $as = null, array $headers = []): Illuminate\Http\Response|Symfony\Component\HttpFoundation\Response
{
    global $app, $cookies, $cookieOwner;

    // a browser session belongs to one user: start a new one when the test switches user
    if (($as?->id) !== $cookieOwner) {
        $cookies = [];
        $cookieOwner = $as?->id;
    }

    // the application object lives across requests here: drop the guards so that no login state leaks between them
    Auth::forgetGuards();
    $as ? Auth::guard()->setUser($as) : Auth::guard()->forgetUser();

    if ($method === 'POST') {
        // a page of the plugin hands out the session cookie and the CSRF token
        $page = http('GET', '/admin/launcheraccess/settings', [], $as);
        preg_match('/name="_token" value="([^"]+)"/', $page->getContent(), $match);
        $data['_token'] = $match[1] ?? '';
    }

    $request = Request::create($uri, $method, $data, $cookies, [], ['HTTP_ACCEPT' => 'text/html'] + $headers);
    $response = $app->make(Illuminate\Contracts\Http\Kernel::class)->handle($request);

    foreach ($response->headers->getCookies() as $cookie) {
        $cookies[$cookie->getName()] = $cookie->getValue();
    }

    return $response;
}

// ---- pages ----------------------------------------------------------------------------------------------
same('settings page for admin', 200, http('GET', '/admin/launcheraccess/settings', [], $admin)->getStatusCode());
same('access page for admin', 200, http('GET', '/admin/launcheraccess', [], $admin)->getStatusCode());
same('overview page for admin (empty catalog)', 200, http('GET', '/admin/launcheraccess/overview', [], $admin)->getStatusCode());

// ---- catalog import -------------------------------------------------------------------------------------
$json = json_encode([
    ['uuid' => P1, 'title' => 'Sky', 'version' => '1.20.1', 'limited' => true, 'optionals' => [
        ['name' => 'Sodium', 'limited' => true], ['name' => 'JEI', 'limited' => false], ['name' => 'Bad*Mod'],
    ]],
    ['uuid' => P2, 'title' => 'Magic', 'limited' => true, 'optionals' => [['name' => 'Botania', 'limited' => true]]],
    ['uuid' => 'broken', 'title' => 'x'],
]);
$response = http('POST', '/admin/launcheraccess/settings/import', ['catalog' => $json], $admin);
check('import redirects', $response->isRedirect(), (string) $response->getStatusCode());
same('two profiles imported', 2, Profile::query()->count());
same('mods imported without the bad one', ['botania', 'jei', 'sodium'], Mod::query()->orderBy('mod_key')->pluck('mod_key')->all());
check('profile is limited', Profile::query()->where('uuid', P1)->first()->limited === true);
http('POST', '/admin/launcheraccess/settings/import', ['catalog' => '{oops'], $admin);
same('broken import changes nothing', 2, Profile::query()->count());

same('access page with a player selected', 200, http('GET', '/admin/launcheraccess?subject=user:'.$steve->id, [], $admin)->getStatusCode());
same('access page with a role selected', 200, http('GET', '/admin/launcheraccess?subject=role:'.$vip->id, [], $admin)->getStatusCode());
same('overview of a profile', 200, http('GET', '/admin/launcheraccess/overview?profile='.P1, [], $admin)->getStatusCode());
same('garbage subject is ignored', 200, http('GET', '/admin/launcheraccess?subject=user:abc', [], $admin)->getStatusCode());

// ---- search ---------------------------------------------------------------------------------------------
$items = json_decode(http('GET', '/admin/launcheraccess/subjects?q=it_', [], $admin)->getContent(), true);
check('search finds players and roles', in_array('user:'.$steve->id, array_column($items, 'id'), true) && in_array('role:'.$vip->id, array_column($items, 'id'), true));
$items = json_decode(http('GET', '/admin/launcheraccess/subjects?q=%25', [], $admin)->getContent(), true);
same('a % in the search is not a wildcard', [], $items);

// ---- grant to a player ----------------------------------------------------------------------------------
$response = http('POST', '/admin/launcheraccess', [
    'subject' => 'user:'.$steve->id, 'profiles' => [P1], 'mods' => [P1.'|sodium'],
], $admin);
check('save redirects', $response->isRedirect());
same('steve got the rows LaunchServer reads', [
    'launcher.runtime.optionals.'.P1.'.sodium.show', 'launchserver.profile.'.P1.'.enter', 'launchserver.profile.'.P1.'.show',
], perms($steve->game_id));
same('alex got nothing', [], perms($alex->game_id));
same('ledger holds the three rows', 3, LedgerRow::query()->where('player_uuid', dashed($steve->game_id))->count());
same('uuid in the table has dashes and lower case', dashed($steve->game_id), DB::connection(AccessSyncer::CONNECTION)->table('user_permissions')->value('uuid'));

// unknown profile / mod / injection attempts are ignored
http('POST', '/admin/launcheraccess', [
    'subject' => 'user:'.$steve->id,
    'profiles' => [P1, '11111111-1111-1111-1111-111111111111'],
    'mods' => [P1.'|sodium', P1.'|*', P1.'|nope', '*'],
], $admin);
$expected = ['launchserver.profile.'.P1.'.enter', 'launchserver.profile.'.P1.'.show', 'launcher.runtime.optionals.'.P1.'.sodium.show'];
sort($expected);
same('only known resources are accepted', $expected, perms($steve->game_id));
check('no wildcard rows anywhere', DB::connection(AccessSyncer::CONNECTION)->table('user_permissions')->where('name', 'like', '%*%')->count() === 0);

// ---- a manual row survives ------------------------------------------------------------------------------
DB::connection(AccessSyncer::CONNECTION)->table('user_permissions')->insert(['uuid' => dashed($steve->game_id), 'name' => 'launcher.debug.test']);
http('POST', '/admin/launcheraccess', ['subject' => 'user:'.$steve->id, 'profiles' => [P1]], $admin);
same('mod revoked, profile kept, manual row kept', [
    'launcher.debug.test', 'launchserver.profile.'.P1.'.enter', 'launchserver.profile.'.P1.'.show',
], perms($steve->game_id));
http('POST', '/admin/launcheraccess', ['subject' => 'user:'.$steve->id], $admin);
same('everything revoked, the manual row is still there', ['launcher.debug.test'], perms($steve->game_id));
same('ledger is empty', 0, LedgerRow::query()->count());

// ---- grant to a role, role changes ----------------------------------------------------------------------
http('POST', '/admin/launcheraccess', ['subject' => 'role:'.$vip->id, 'profiles' => [P2], 'mods' => [P2.'|botania']], $admin);
same('role without members writes nothing', [], perms($alex->game_id));

$alex->forceFill(['role_id' => $vip->id])->save();
same('alex became VIP and got the role rows', [
    'launcher.runtime.optionals.'.P2.'.botania.show', 'launchserver.profile.'.P2.'.enter', 'launchserver.profile.'.P2.'.show',
], perms($alex->game_id));
same('steve is not affected', ['launcher.debug.test'], perms($steve->game_id));

$alex->forceFill(['role_id' => $member->id])->save();
same('alex left the role and lost the rows', [], perms($alex->game_id));
$alex->forceFill(['role_id' => $vip->id])->save();

// the admin can see who has what
$overview = http('GET', '/admin/launcheraccess/overview?profile='.P2, [], $admin)->getContent();
check('overview shows the role and its member', str_contains($overview, 'it_vip') && str_contains($overview, 'it_alex'));

// ---- game id changes and deletion -----------------------------------------------------------------------
$old = $alex->game_id;
$new = offlineGameId('it_alex_renamed');
$alex->forceFill(['game_id' => $new])->save();
same('old game id is cleaned', [], perms($old));
same('new game id has the rows', 3, count(perms($new)));

$alex->delete();
same('deleted player is cleaned', [], perms($new));
same('and so is his ledger', 0, LedgerRow::query()->where('player_uuid', dashed($new))->count());

// ---- players without game id ----------------------------------------------------------------------------
http('POST', '/admin/launcheraccess', ['subject' => 'user:'.$nogame->id, 'profiles' => [P1]], $admin);
same('player without game id: rules saved, nothing written', 1, Rule::query()->where('subject_id', $nogame->id)->where('subject_type', 'user')->count());
$nogame->forceFill(['game_id' => offlineGameId('it_nogame')])->save();
same('after his first login the rows appear', ['launchserver.profile.'.P1.'.enter', 'launchserver.profile.'.P1.'.show'], perms($nogame->game_id));

// ---- full sync, idempotence, dry run -------------------------------------------------------------------
$syncer = AccessSyncer::fromSettings();
$report = $syncer->syncAll();
same('a full sync right after is a no-op', [0, 0], [$report['insert'], $report['delete']]);

DB::connection(AccessSyncer::CONNECTION)->table('user_permissions')->where('uuid', dashed($nogame->game_id))->delete();
$dry = $syncer->syncAll(true);
same('dry run reports the missing rows', 2, $dry['insert']);
same('dry run writes nothing', [], perms($nogame->game_id));
$real = $syncer->syncAll();
same('the real run repairs them', 2, $real['insert']);

Artisan::call('launcheraccess:sync', ['--dry-run' => true]);
check('artisan command works', str_contains(Artisan::output(), 'Dry run'));

// a ledger row whose rule was removed behind our back (rules table edited by hand) is cleaned by the full sync
Rule::query()->where('subject_type', 'user')->where('subject_id', $nogame->id)->delete();
$report = $syncer->syncAll();
same('rows of a vanished rule are removed by the full sync', [0, 2], [$report['insert'], $report['delete']]);
same('nogame has nothing again', [], perms($nogame->game_id));

// ---- settings -------------------------------------------------------------------------------------------
$bad = http('POST', '/admin/launcheraccess/settings', [
    'table' => 'user_permissions; DROP TABLE users', 'uuid_column' => 'uuid', 'permission_column' => 'name', 'max_length' => 100,
], $admin);
check('evil table name is refused', $bad->isRedirect() && ! Schema::hasTable('users') === false);
check('users table is intact', Schema::hasTable('users'));
same('setting was not stored', null, DB::table('settings')->where('name', 'launcheraccess.table')->value('value'));

http('POST', '/admin/launcheraccess/settings', ['table' => 'nope', 'uuid_column' => 'uuid', 'permission_column' => 'name', 'max_length' => 100], $admin);
same('unknown table is refused', null, DB::table('settings')->where('name', 'launcheraccess.table')->value('value'));

http('POST', '/admin/launcheraccess/settings', ['table' => 'user_permissions', 'uuid_column' => 'uuid', 'permission_column' => 'name', 'max_length' => 100, 'auto_sync' => 'on'], $admin);
same('valid settings are stored', 'user_permissions', DB::table('settings')->where('name', 'launcheraccess.table')->value('value'));

$sync = http('POST', '/admin/launcheraccess/settings/sync', ['mode' => 'preview'], $admin);
check('preview from the settings page', $sync->isRedirect());

// ---- identifiers ----------------------------------------------------------------------------------------
foreach (['user_permissions', 'a1', 'A_b'] as $ok) {
    check("identifier $ok", AccessSyncer::isIdentifier($ok));
}
foreach (["x\n", 'a b', 'a;b', 'a`b', '', str_repeat('a', 65), 'a.b'] as $evil) {
    check('identifier rejected '.json_encode($evil), ! AccessSyncer::isIdentifier($evil));
}

// ---- a player without the admin right (last: the test application keeps login state between requests) ------
$rulesBefore = Rule::query()->count();
check('plain player cannot open the page', in_array(http('GET', '/admin/launcheraccess', [], $steve)->getStatusCode(), [302, 403], true));
check('plain player cannot post', in_array(http('POST', '/admin/launcheraccess', ['subject' => 'user:'.$steve->id, 'profiles' => [P1]], $steve)->getStatusCode(), [302, 403, 419], true));
same('nothing was written by that', $rulesBefore, Rule::query()->count());

// ---- clean up -------------------------------------------------------------------------------------------
foreach ([LedgerRow::class, Rule::class, Mod::class, Profile::class] as $model) {
    $model::query()->delete();
}
cleanUsers();
Role::query()->whereIn('name', ['it_admin_role', 'it_member', 'it_vip'])->delete();
if ($createdDefaultRole) {
    Role::query()->whereKey($defaultRoleId)->delete();
}
Schema::connection(AccessSyncer::CONNECTION)->dropIfExists('user_permissions');
DB::table('settings')->where('name', 'like', 'launcheraccess.%')->delete();

echo "$total checks, $failed failed\n";
exit($failed ? 1 : 0);
