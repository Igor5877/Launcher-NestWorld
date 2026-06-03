<?php

namespace Azuriom\Plugin\Launcher\Controllers\Admin;

use Azuriom\Http\Controllers\Controller;
use Azuriom\Models\Role;
use Azuriom\Models\User;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;

class LauncherController extends Controller
{
    public function index()
    {
        $profiles = DB::table('launcher_profiles')->orderBy('name')->get();

        $profilesWithData = $profiles->map(function ($profile) {
            $access = DB::table('launcher_access')
                ->where('profile_uuid', $profile->uuid)
                ->get();

            $profileAccess = $access->whereNull('mod_name');
            $modAccess     = $access->whereNotNull('mod_name');

            $mods = DB::table('launcher_mods')
                ->where('profile_uuid', $profile->uuid)
                ->orderBy('name')
                ->get();

            return array_merge((array) $profile, [
                'roles'      => $profileAccess->where('subject_type', 'role')->values(),
                'players'    => $this->resolvePlayerNames($profileAccess->where('subject_type', 'player')->values()),
                'mods'       => $mods->map(function ($mod) use ($modAccess) {
                    $modRoles   = $modAccess->where('mod_name', $mod->name)->where('subject_type', 'role')->values();
                    $modPlayers = $this->resolvePlayerNames(
                        $modAccess->where('mod_name', $mod->name)->where('subject_type', 'player')->values()
                    );
                    return array_merge((array) $mod, [
                        'roles'   => $modRoles,
                        'players' => $modPlayers,
                    ]);
                }),
            ]);
        });

        return view('launcher::admin.index', [
            'profiles' => $profilesWithData,
            'allRoles' => Role::orderBy('name')->get(),
        ]);
    }

    public function grantRole(Request $request)
    {
        $data = $request->validate([
            'profile_uuid' => ['required', 'exists:launcher_profiles,uuid'],
            'role_name'    => ['required', 'string'],
            'mod_name'     => ['nullable', 'string'],
        ]);

        DB::table('launcher_access')->updateOrInsert(
            [
                'profile_uuid' => $data['profile_uuid'],
                'subject_type' => 'role',
                'subject_id'   => $data['role_name'],
                'mod_name'     => $data['mod_name'] ?? null,
            ],
            ['created_at' => now(), 'updated_at' => now()]
        );

        $target = $data['mod_name'] ? 'мод "' . $data['mod_name'] . '"' : 'профіль';
        return back()->with('success', 'Роль "' . $data['role_name'] . '" отримала доступ до ' . $target . '.');
    }

    public function grantPlayer(Request $request)
    {
        $data = $request->validate([
            'profile_uuid' => ['required', 'exists:launcher_profiles,uuid'],
            'player_name'  => ['required', 'string'],
            'mod_name'     => ['nullable', 'string'],
        ]);

        $user = User::where('name', $data['player_name'])->first();
        if (!$user) {
            return back()->withErrors(['player_name' => 'Гравця "' . $data['player_name'] . '" не знайдено.']);
        }

        DB::table('launcher_access')->updateOrInsert(
            [
                'profile_uuid' => $data['profile_uuid'],
                'subject_type' => 'player',
                'subject_id'   => $user->game_id ?? $user->name,
                'mod_name'     => $data['mod_name'] ?? null,
            ],
            ['created_at' => now(), 'updated_at' => now()]
        );

        $target = $data['mod_name'] ? 'мод "' . $data['mod_name'] . '"' : 'профіль';
        return back()->with('success', 'Гравець "' . $user->name . '" отримав доступ до ' . $target . '.');
    }

    public function searchUsers(Request $request)
    {
        $q = $request->validate(['q' => ['required', 'string', 'min:2', 'max:32']])['q'];

        $users = User::where('name', 'like', $q . '%')
            ->orderBy('name')
            ->limit(8)
            ->get(['name', 'game_id'])
            ->map(fn($u) => ['name' => $u->name, 'uuid' => $u->game_id]);

        return response()->json($users);
    }

    public function stats()
    {
        $profiles = DB::table('launcher_profiles')->count();
        $roleMappings = DB::table('launcher_access')->whereNull('mod_name')->where('subject_type', 'role')->count();
        $playerGrants = DB::table('launcher_access')->whereNull('mod_name')->where('subject_type', 'player')->count();
        $limitedMods = DB::table('launcher_mods')->count();

        return response()->json(compact('profiles', 'roleMappings', 'playerGrants', 'limitedMods'));
    }

    public function revoke(int $id)
    {
        DB::table('launcher_access')->where('id', $id)->delete();
        return back()->with('success', 'Доступ відкликано.');
    }

    private function resolvePlayerNames($entries)
    {
        return $entries->map(function ($entry) {
            $user = User::where('game_id', $entry->subject_id)
                ->orWhere('name', $entry->subject_id)
                ->first();
            $entry->display = $user ? $user->name : $entry->subject_id;
            return $entry;
        })->values();
    }
}
