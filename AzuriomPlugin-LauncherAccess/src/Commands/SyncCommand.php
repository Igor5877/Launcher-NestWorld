<?php

namespace Azuriom\Plugin\LauncherAccess\Commands;

use Azuriom\Plugin\LauncherAccess\Services\AccessSyncer;
use Illuminate\Console\Command;

class SyncCommand extends Command
{
    protected $signature = 'launcheraccess:sync {--dry-run : Only show what would change}';

    protected $description = 'Write the launcher access rules into the LaunchServer permissions table';

    public function handle(): int
    {
        $dry = (bool) $this->option('dry-run');
        $report = AccessSyncer::fromSettings()->syncAll($dry);

        $this->info(sprintf('%s: %d players, %d rows to add, %d rows to remove.',
            $dry ? 'Dry run' : 'Synced', $report['players'], $report['insert'], $report['delete']));

        foreach ($report['warnings'] as $warning) {
            $this->warn($warning['type'].': '.$warning['detail']);
        }

        return self::SUCCESS;
    }
}
