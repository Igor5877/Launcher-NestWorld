<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        // Mods synced automatically from the launcher server
        Schema::create('launcher_mods', function (Blueprint $table) {
            $table->id();
            $table->string('profile_uuid', 36);
            $table->string('name');          // OptionalFile.name (lowercase)
            $table->string('info')->nullable();
            $table->timestamps();

            $table->unique(['profile_uuid', 'name']);
            $table->foreign('profile_uuid')
                  ->references('uuid')
                  ->on('launcher_profiles')
                  ->onDelete('cascade');
        });

        // Add optional mod_name column to launcher_access.
        // NULL  → profile-level access (show + enter)
        // 'foo' → mod-level access (show only)
        Schema::table('launcher_access', function (Blueprint $table) {
            $table->string('mod_name')->nullable()->default(null)->after('subject_id');

            $table->dropUnique(['profile_uuid', 'subject_type', 'subject_id']);
            $table->unique(['profile_uuid', 'subject_type', 'subject_id', 'mod_name']);
        });
    }

    public function down(): void
    {
        Schema::table('launcher_access', function (Blueprint $table) {
            $table->dropUnique(['profile_uuid', 'subject_type', 'subject_id', 'mod_name']);
            $table->dropColumn('mod_name');
            $table->unique(['profile_uuid', 'subject_type', 'subject_id']);
        });

        Schema::dropIfExists('launcher_mods');
    }
};
