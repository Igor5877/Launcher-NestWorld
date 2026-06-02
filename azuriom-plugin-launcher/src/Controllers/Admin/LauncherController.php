<?php

namespace Azuriom\Plugin\Launcher\Controllers\Admin;

use Azuriom\Http\Controllers\Controller;
use Azuriom\Models\Role;
use Azuriom\Models\User;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Illuminate\Validation\Rule;

class LauncherController extends Controller
{
    public function index()
    {
        $profiles = DB::table('launcher_profiles')->orderBy('name')->get();

        $profilesWithAccess = $profiles->map(function ($profile) {
            $access = DB::table('launcher_access')
                ->where('profile_uuid', $profile->uuid)
                ->get();

            $roles   = $access->where('subject_type', 'role')->values();
            $players = $access->where('subject_type', 'player')->map(function ($entry) {
                $user = User::where('game_id', $entry->subject_id)
                    ->orWhere('name', $entry->subject_id)
                    ->first();
                $entry->display = $user ? $user->name : $entry->subject_id;
                return $entry;
            })->values();

            return array_merge((array) $profile, [
                'roles'   => $roles,
                'players' => $players,
            ]);
        });

        $allRoles = Role::orderBy('name')->get();

        return view('launcher::admin.index', [
            'profiles'    => $profilesWithAccess,
            'allRoles'    => $allRoles,
        ]);
    }

    public function createProfile(Request $request)
    {
        $data = $request->validate([
            'uuid' => ['required', 'regex:/^[0-9a-f\-]{36}$/i',
                        Rule::unique('launcher_profiles', 'uuid')],
            'name' => ['required', 'string', 'max:255'],
            'description' => ['nullable', 'string', 'max:255'],
        ]);

        DB::table('launcher_profiles')->insert([
            'uuid'        => $data['uuid'],
            'name'        => $data['name'],
            'description' => $data['description'] ?? null,
            'created_at'  => now(),
            'updated_at'  => now(),
        ]);

        return back()->with('success', 'Профіль "' . $data['name'] . '" додано.');
    }

    public function deleteProfile(string $uuid)
    {
        DB::table('launcher_profiles')->where('uuid', $uuid)->delete();
        return back()->with('success', 'Профіль видалено.');
    }

    public function grantRole(Request $request)
    {
        $data = $request->validate([
            'profile_uuid' => ['required', 'exists:launcher_profiles,uuid'],
            'role_name'    => ['required', 'string'],
        ]);

        DB::table('launcher_access')->updateOrInsert(
            [
                'profile_uuid' => $data['profile_uuid'],
                'subject_type' => 'role',
                'subject_id'   => $data['role_name'],
            ],
            ['created_at' => now(), 'updated_at' => now()]
        );

        return back()->with('success', 'Роль "' . $data['role_name'] . '" отримала доступ до профілю.');
    }

    public function grantPlayer(Request $request)
    {
        $data = $request->validate([
            'profile_uuid' => ['required', 'exists:launcher_profiles,uuid'],
            'player_name'  => ['required', 'string'],
        ]);

        $user = User::where('name', $data['player_name'])->first();
        if (!$user) {
            return back()->withErrors(['player_name' => 'Гравця "' . $data['player_name'] . '" не знайдено.']);
        }

        $playerId = $user->game_id ?? $user->name;

        DB::table('launcher_access')->updateOrInsert(
            [
                'profile_uuid' => $data['profile_uuid'],
                'subject_type' => 'player',
                'subject_id'   => $playerId,
            ],
            ['created_at' => now(), 'updated_at' => now()]
        );

        return back()->with('success', 'Гравець "' . $user->name . '" отримав особистий доступ до профілю.');
    }

    public function revoke(int $id)
    {
        DB::table('launcher_access')->where('id', $id)->delete();
        return back()->with('success', 'Доступ відкликано.');
    }
}
