<?php

namespace Azuriom\Plugin\LauncherAccess\Models;

use Illuminate\Database\Eloquent\Model;

/**
 * A row the plugin inserted into user_permissions.
 *
 * @property string $player_uuid
 * @property string $permission
 */
class LedgerRow extends Model
{
    protected $table = 'launcher_access_managed';

    public $timestamps = false;

    protected $fillable = ['player_uuid', 'permission'];
}
