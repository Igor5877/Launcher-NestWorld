<?php

namespace Azuriom\Plugin\LauncherAccess\Models;

use Illuminate\Database\Eloquent\Model;

/**
 * @property string $profile_uuid
 * @property string $mod_key
 * @property string $name
 * @property bool $limited
 */
class Mod extends Model
{
    protected $table = 'launcher_access_mods';

    protected $fillable = ['profile_uuid', 'mod_key', 'name', 'category', 'info', 'limited', 'visible'];

    protected $casts = ['limited' => 'boolean', 'visible' => 'boolean'];
}
