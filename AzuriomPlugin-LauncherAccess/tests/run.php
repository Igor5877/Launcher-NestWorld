<?php

// Dependency free tests of the pure logic: php tests/run.php

require __DIR__.'/../src/Support/PermissionFormat.php';
require __DIR__.'/../src/Support/DesiredBuilder.php';
require __DIR__.'/../src/Support/SyncPlanner.php';
require __DIR__.'/../src/Support/CatalogParser.php';

use Azuriom\Plugin\LauncherAccess\Support\CatalogParser;
use Azuriom\Plugin\LauncherAccess\Support\DesiredBuilder;
use Azuriom\Plugin\LauncherAccess\Support\PermissionFormat as F;
use Azuriom\Plugin\LauncherAccess\Support\SyncPlanner;

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

const P1 = '00000000-0000-0000-0000-000000000001';
const P2 = '00000000-0000-0000-0000-000000000002';
const U1 = '011b1b61-7039-421b-9f4d-31fcf7a195ef';
const U2 = '5b7c3f8e-2a8c-3b3c-8b0b-0d7c0c7a0f11';

// ---- uuid ---------------------------------------------------------------------------------------------
same('uuid from game_id without dashes', U1, F::uuid('011b1b6170394'.'21b9f4d31fcf7a195ef'));
same('uuid from game_id', U1, F::uuid('011b1b61703'.'9421b9f4d31fcf7a195ef'));
same('uuid from upper case', U1, F::uuid(strtoupper(str_replace('-', '', U1))));
same('uuid with dashes stays', U1, F::uuid(U1));
same('uuid null', null, F::uuid(null));
same('uuid garbage', null, F::uuid('not-a-uuid'));
same('uuid too short', null, F::uuid('abc'));
same('uuid with spaces trimmed', U1, F::uuid("  ".U1." "));

// ---- mod key ------------------------------------------------------------------------------------------
same('mod key lower case', 'sodium', F::modKey('Sodium'));
same('mod key keeps spaces', 'ender io', F::modKey('Ender IO'));
same('mod key unicode', 'мод', F::modKey('МОД'));
same('mod key star rejected', null, F::modKey('a*b'));
same('mod key control rejected', null, F::modKey("a\nb"));
same('mod key empty', null, F::modKey(''));

// ---- formats ------------------------------------------------------------------------------------------
same('profile rows', ['launchserver.profile.'.P1.'.show', 'launchserver.profile.'.P1.'.enter'], F::profile(P1));
same('mod row', 'launcher.runtime.optionals.'.P1.'.sodium.show', F::modShow(P1, 'sodium'));

check('managed: profile show', F::isManaged(F::profileShow(P1)));
check('managed: profile enter', F::isManaged(F::profileEnter(P1)));
check('managed: mod', F::isManaged(F::modShow(P1, 'ender io')));
foreach (['*', 'launchserver.*', 'launchserver.profile.*.show', 'launchserver.profile.'.P1.'.*',
    'launchserver.profile.nestworld.show', 'launcher.*', 'role.admin', 'launchserver.checkserver',
    'launcher.runtime.optionals.'.P1.'.*.show', 'launcher.runtime.optionals.'.P1.'.sodium.hide',
    "launchserver.profile.".P1.".show\n", 'launchserver.profile.'.strtoupper(U1).'.show'] as $evil) {
    check('not managed: '.json_encode($evil), ! F::isManaged($evil));
}

check('fits', F::fits(str_repeat('a', 100)) && ! F::fits(str_repeat('a', 101)));

same('table uuid dashless', str_replace('-', '', U1), F::tableUuid(U1, 'dashless'));
same('table uuid dashed', U1, F::tableUuid(U1, 'dashed'));

// ---- desired ------------------------------------------------------------------------------------------
$users = [
    ['id' => 1, 'game_id' => str_replace('-', '', U1), 'role_id' => 5],
    ['id' => 2, 'game_id' => str_replace('-', '', U2), 'role_id' => 6],
    ['id' => 3, 'game_id' => null, 'role_id' => 5],
];
$rules = [
    ['subject_type' => 'user', 'subject_id' => 1, 'resource_type' => 'profile', 'profile_uuid' => P1, 'mod_key' => null],
    ['subject_type' => 'role', 'subject_id' => 5, 'resource_type' => 'profile', 'profile_uuid' => P2, 'mod_key' => null],
    ['subject_type' => 'user', 'subject_id' => 1, 'resource_type' => 'mod', 'profile_uuid' => P1, 'mod_key' => 'Sodium'],
];
$w = [];
$d = DesiredBuilder::build($users, $rules, 100, $w);
same('players listed', [U1, U2], array_keys($d));
same('user 1: own profile + role profile + mod', [
    F::modShow(P1, 'sodium'), F::profileEnter(P1), F::profileShow(P1), F::profileEnter(P2), F::profileShow(P2),
], $d[U1]);
same('user 2 without rules still present (to clean old rows)', [], $d[U2]);
check('user 3 without game_id warned', in_array(['type' => 'no_game_id', 'detail' => '3'], $w, true));

$w = [];
$d = DesiredBuilder::build([['id' => 1, 'game_id' => U1, 'role_id' => null]], [
    ['subject_type' => 'user', 'subject_id' => 1, 'resource_type' => 'mod', 'profile_uuid' => P1, 'mod_key' => 'sodium'],
], 100, $w);
check('mod without profile is warned', in_array('mod_without_profile', array_column($w, 'type'), true));

$w = [];
DesiredBuilder::build([['id' => 1, 'game_id' => U1, 'role_id' => null]], [
    ['subject_type' => 'user', 'subject_id' => 1, 'resource_type' => 'mod', 'profile_uuid' => P1, 'mod_key' => 'sodium'],
], 100, $w, [P1 => true]);
same('no warning when the profile is public', [], $w);
same('mod row is still written', [F::modShow(P1, 'sodium')], $d[U1]);

$w = [];
$d = DesiredBuilder::build([['id' => 1, 'game_id' => U1, 'role_id' => null]], [
    ['subject_type' => 'user', 'subject_id' => 1, 'resource_type' => 'mod', 'profile_uuid' => P1, 'mod_key' => str_repeat('x', 80)],
    ['subject_type' => 'user', 'subject_id' => 1, 'resource_type' => 'profile', 'profile_uuid' => 'garbage', 'mod_key' => null],
    ['subject_type' => 'user', 'subject_id' => 1, 'resource_type' => 'mod', 'profile_uuid' => P1, 'mod_key' => 'a*b'],
], 100, $w);
same('too long, garbage and star rows are dropped', [], $d[U1]);
same('their warnings', ['bad_profile', 'bad_mod', 'mod_without_profile', 'too_long'], array_column($w, 'type'));

$d = DesiredBuilder::build([['id' => 1, 'game_id' => U1, 'role_id' => 5]], [
    ['subject_type' => 'user', 'subject_id' => 1, 'resource_type' => 'profile', 'profile_uuid' => strtoupper(P1), 'mod_key' => null],
    ['subject_type' => 'role', 'subject_id' => 5, 'resource_type' => 'profile', 'profile_uuid' => P1, 'mod_key' => null],
], 100);
same('same profile from user and role is written once', [F::profileEnter(P1), F::profileShow(P1)], $d[U1]);

// ---- planner ------------------------------------------------------------------------------------------
$show = F::profileShow(P1);
$enter = F::profileEnter(P1);

$p = SyncPlanner::plan([$show, $enter], [], []);
same('first sync inserts everything', [[$enter, $show], [], [$enter, $show]], [$p['insert'], $p['delete'], $p['ledger']]);

$p = SyncPlanner::plan([$show, $enter], [$show, $enter], [$show, $enter]);
same('second sync does nothing (idempotent)', [[], [], [$enter, $show]], [$p['insert'], $p['delete'], $p['ledger']]);

$p = SyncPlanner::plan([$show], [$show, $enter], [$show, $enter]);
same('revoked row is deleted', [[], [$enter], [$show]], [$p['insert'], $p['delete'], $p['ledger']]);

$p = SyncPlanner::plan([$show], [$show, 'launchserver.profile.'.P2.'.show', '*'], [$show]);
same('manual rows are never deleted', [[], [], [$show]], [$p['insert'], $p['delete'], $p['ledger']]);

$p = SyncPlanner::plan([$show], [$show], []);
same('a manual row that equals a desired one is not taken over', [[], [], []], [$p['insert'], $p['delete'], $p['ledger']]);

$p = SyncPlanner::plan([$show, $enter], [$show], [$show, $enter]);
same('row someone removed by hand is restored', [[$enter], [], [$enter, $show]], [$p['insert'], $p['delete'], $p['ledger']]);

$p = SyncPlanner::plan([], [$show], ['*', 'role.admin', $show]);
same('only whitelisted formats are deleted even if the ledger holds anything', [[], [$show], []], [$p['insert'], $p['delete'], $p['ledger']]);

$p = SyncPlanner::plan([], [], [$show]);
same('ledger row that vanished is forgotten', [[], [], []], [$p['insert'], $p['delete'], $p['ledger']]);

$p = SyncPlanner::plan([$show, $show], [$show, $show], []);
same('duplicates in the table are tolerated', [[], [], []], [$p['insert'], $p['delete'], $p['ledger']]);

// ---- catalog ------------------------------------------------------------------------------------------
$json = json_encode([
    ['uuid' => str_replace('-', '', strtoupper(P1)), 'title' => 'Sky', 'version' => '1.20.1', 'limited' => true, 'sortIndex' => 3,
        'optionals' => [
            ['name' => 'Sodium', 'limited' => true, 'category' => 'Graphics', 'info' => 'faster'],
            ['name' => 'Bad*Name'],
            ['name' => 'Sodium'],
            ['name' => 'JEI', 'visible' => false],
        ]],
    ['uuid' => 'nope', 'title' => 'x'],
    'string',
]);
$c = CatalogParser::parse($json);
same('catalog: one profile', 1, count($c['profiles']));
same('catalog: profile', ['uuid' => P1, 'title' => 'Sky', 'version' => '1.20.1', 'limited' => true, 'sort_index' => 3], $c['profiles'][0]);
same('catalog: mods deduplicated, bad one dropped', ['sodium', 'jei'], array_column($c['mods'], 'mod_key'));
same('catalog: mod flags', [true, false], array_column($c['mods'], 'limited'));
same('catalog: visible', [true, false], array_column($c['mods'], 'visible'));
same('catalog: three problems reported', 3, count($c['errors']));
same('catalog: broken json', ['invalid_json'], CatalogParser::parse('{oops')['errors']);

$single = CatalogParser::parse(json_encode(['uuid' => P2, 'title' => 'One', 'limited' => false, 'updateOptional' => [['name' => 'A']]]));
same('catalog: a single LaunchServer profile file', [P2], array_column($single['profiles'], 'uuid'));
same('catalog: updateOptional is read', ['a'], array_column($single['mods'], 'mod_key'));

echo "$total checks, $failed failed\n";
exit($failed ? 1 : 0);
