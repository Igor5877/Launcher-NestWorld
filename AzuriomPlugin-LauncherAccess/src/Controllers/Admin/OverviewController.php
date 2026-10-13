<?php

namespace Azuriom\Plugin\LauncherAccess\Controllers\Admin;

use Azuriom\Http\Controllers\Controller;
use Azuriom\Models\Role;
use Azuriom\Models\User;
use Azuriom\Plugin\LauncherAccess\Models\Mod;
use Azuriom\Plugin\LauncherAccess\Models\Profile;
use Azuriom\Plugin\LauncherAccess\Models\Rule;
use Illuminate\Http\Request;

class OverviewController extends Controller
{
    /**
     * "Who has access to what": for one profile, who may use it and each of its limited mods.
     */
    public function index(Request $request)
    {
        $profiles = Profile::query()->orderBy('sort_index')->orderBy('title')->get();
        $profile = $profiles->firstWhere('uuid', (string) $request->query('profile')) ?? $profiles->first();

        $sections = [];

        if ($profile !== null) {
            $rules = Rule::query()->where('profile_uuid', $profile->uuid)->get();

            $users = User::query()->whereIn('id', $rules->where('subject_type', 'user')->pluck('subject_id')->unique())
                ->get(['id', 'name'])->keyBy('id');
            $roles = Role::query()->whereIn('id', $rules->where('subject_type', 'role')->pluck('subject_id')->unique())
                ->get(['id', 'name'])->keyBy('id');

            $sections[] = $this->section($profile->title, $profile->limited, $rules->where('resource_type', 'profile'), $users, $roles, true);

            foreach (Mod::query()->where('profile_uuid', $profile->uuid)->orderBy('name')->get() as $mod) {
                $sections[] = $this->section($mod->name, $mod->limited,
                    $rules->where('resource_type', 'mod')->where('mod_key', $mod->mod_key), $users, $roles, false);
            }
        }

        return view('launcheraccess::admin.overview', [
            'profiles' => $profiles,
            'profile' => $profile,
            'sections' => $sections,
        ]);
    }

    /**
     * @return array{title:string, limited:bool, isProfile:bool, users:string[], roles:array<int, array{name:string, members:int, names:string[]}>}
     */
    private function section(string $title, bool $limited, $rules, $users, $roles, bool $isProfile): array
    {
        $roleRows = [];

        foreach ($rules->where('subject_type', 'role') as $rule) {
            $role = $roles->get($rule->subject_id);

            if ($role === null) {
                continue;
            }

            $members = User::query()->where('role_id', $role->id);
            $roleRows[] = [
                'name' => $role->name,
                'members' => (clone $members)->count(),
                'names' => (clone $members)->orderBy('name')->limit(20)->pluck('name')->all(),
            ];
        }

        return [
            'title' => $title,
            'limited' => $limited,
            'isProfile' => $isProfile,
            'users' => $rules->where('subject_type', 'user')->map(fn (Rule $rule) => $users->get($rule->subject_id)?->name)->filter()->sort()->values()->all(),
            'roles' => $roleRows,
        ];
    }
}
