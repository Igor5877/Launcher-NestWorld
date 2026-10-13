<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Run the migrations.
     */
    public function up(): void
    {
        // Profiles and mods LaunchServer has: filled by the LaunchServer exporter module or by the JSON import
        Schema::create('launcher_access_profiles', function (Blueprint $table) {
            $table->id();
            $table->char('uuid', 36)->unique();
            $table->string('title');
            $table->string('version', 64)->nullable();
            $table->boolean('limited')->default(false);
            $table->integer('sort_index')->default(0);
            $table->timestamp('synced_at')->nullable();
            $table->timestamps();
        });

        Schema::create('launcher_access_mods', function (Blueprint $table) {
            $table->id();
            $table->char('profile_uuid', 36);
            $table->string('mod_key', 100);
            $table->string('name');
            $table->string('category', 100)->nullable();
            $table->string('info', 1000)->nullable();
            $table->boolean('limited')->default(false);
            $table->boolean('visible')->default(true);
            $table->timestamps();

            $table->unique(['profile_uuid', 'mod_key']);
        });

        // What the admin chose: who (user or role) may see which profile / mod
        Schema::create('launcher_access_rules', function (Blueprint $table) {
            $table->id();
            $table->string('subject_type', 8); // user | role
            $table->unsignedBigInteger('subject_id');
            $table->string('resource_type', 8); // profile | mod
            $table->char('profile_uuid', 36);
            $table->string('mod_key', 100)->default(''); // '' for a profile
            $table->unsignedBigInteger('created_by')->nullable();
            $table->timestamps();

            $table->unique(['subject_type', 'subject_id', 'resource_type', 'profile_uuid', 'mod_key'], 'launcher_access_rules_unique');
            $table->index(['profile_uuid', 'mod_key']);
        });

        // Rows the plugin itself inserted into user_permissions (it never deletes anything else)
        Schema::create('launcher_access_managed', function (Blueprint $table) {
            $table->id();
            $table->char('player_uuid', 36);
            $table->string('permission');

            $table->unique(['player_uuid', 'permission'], 'launcher_access_managed_unique');
        });
    }

    /**
     * Reverse the migrations.
     */
    public function down(): void
    {
        Schema::dropIfExists('launcher_access_managed');
        Schema::dropIfExists('launcher_access_rules');
        Schema::dropIfExists('launcher_access_mods');
        Schema::dropIfExists('launcher_access_profiles');
    }
};
