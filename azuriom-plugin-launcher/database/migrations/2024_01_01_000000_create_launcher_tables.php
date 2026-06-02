<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::create('launcher_profiles', function (Blueprint $table) {
            $table->string('uuid', 36)->primary();
            $table->string('name');
            $table->string('description')->nullable();
            $table->timestamps();
        });

        Schema::create('launcher_access', function (Blueprint $table) {
            $table->id();
            $table->string('profile_uuid', 36);
            $table->enum('subject_type', ['role', 'player']);
            $table->string('subject_id'); // role name OR player UUID
            $table->timestamps();

            $table->unique(['profile_uuid', 'subject_type', 'subject_id']);
            $table->index(['subject_type', 'subject_id']);
            $table->foreign('profile_uuid')
                  ->references('uuid')
                  ->on('launcher_profiles')
                  ->onDelete('cascade');
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('launcher_access');
        Schema::dropIfExists('launcher_profiles');
    }
};
