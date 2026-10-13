<?php

namespace Azuriom\Plugin\LauncherAccess\Models;

use Illuminate\Database\Eloquent\Model;

/**
 * @property string $subject_type user|role
 * @property int $subject_id
 * @property string $resource_type profile|mod
 * @property string $profile_uuid
 * @property string $mod_key
 */
class Rule extends Model
{
    protected $table = 'launcher_access_rules';

    protected $fillable = ['subject_type', 'subject_id', 'resource_type', 'profile_uuid', 'mod_key', 'created_by'];
}
