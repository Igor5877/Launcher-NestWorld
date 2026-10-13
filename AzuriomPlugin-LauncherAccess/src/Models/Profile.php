<?php

namespace Azuriom\Plugin\LauncherAccess\Models;

use Illuminate\Database\Eloquent\Model;

/**
 * @property string $uuid
 * @property string $title
 * @property bool $limited
 */
class Profile extends Model
{
    protected $table = 'launcher_access_profiles';

    protected $fillable = ['uuid', 'title', 'version', 'limited', 'sort_index', 'synced_at'];

    protected $casts = ['limited' => 'boolean', 'synced_at' => 'datetime'];
}
