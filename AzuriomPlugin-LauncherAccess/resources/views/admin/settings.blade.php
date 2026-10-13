@extends('admin.layouts.admin')

@section('title', trans('launcheraccess::admin.settings.title'))

@section('content')
    @if(session('import_errors'))
        <div class="alert alert-warning">
            <strong>{{ trans('launcheraccess::admin.settings.import_problems') }}</strong>
            <ul class="mb-0">
                @foreach(session('import_errors') as $problem)
                    <li>{{ $problem }}</li>
                @endforeach
            </ul>
        </div>
    @endif

    @if(session('sync_warnings'))
        <div class="alert alert-warning">
            <ul class="mb-0">
                @foreach(session('sync_warnings') as $warning)
                    <li>{{ trans('launcheraccess::admin.warnings.'.$warning['type']) }}: {{ $warning['detail'] }}</li>
                @endforeach
            </ul>
        </div>
    @endif

    <div class="card shadow mb-4">
        <div class="card-header"><strong>{{ trans('launcheraccess::admin.settings.table_section') }}</strong></div>
        <div class="card-body">
            <form action="{{ route('launcheraccess.admin.settings.save') }}" method="POST">
                @csrf

                <div class="row g-3 mb-3">
                    <div class="col-md-4">
                        <label class="form-label" for="table">{{ trans('launcheraccess::admin.settings.table') }}</label>
                        <input type="text" class="form-control @error('table') is-invalid @enderror" id="table" name="table"
                               value="{{ old('table', $table) }}" required pattern="[A-Za-z0-9_]{1,64}">
                        @error('table')<div class="invalid-feedback">{{ $message }}</div>@enderror
                    </div>
                    <div class="col-md-3">
                        <label class="form-label" for="uuid_column">{{ trans('launcheraccess::admin.settings.uuid_column') }}</label>
                        <input type="text" class="form-control @error('uuid_column') is-invalid @enderror" id="uuid_column" name="uuid_column"
                               value="{{ old('uuid_column', $uuidColumn) }}" required pattern="[A-Za-z0-9_]{1,64}">
                    </div>
                    <div class="col-md-3">
                        <label class="form-label" for="permission_column">{{ trans('launcheraccess::admin.settings.permission_column') }}</label>
                        <input type="text" class="form-control @error('permission_column') is-invalid @enderror" id="permission_column" name="permission_column"
                               value="{{ old('permission_column', $permissionColumn) }}" required pattern="[A-Za-z0-9_]{1,64}">
                    </div>
                    <div class="col-md-2">
                        <label class="form-label" for="max_length">{{ trans('launcheraccess::admin.settings.max_length') }}</label>
                        <input type="number" class="form-control @error('max_length') is-invalid @enderror" id="max_length" name="max_length"
                               value="{{ old('max_length', $maxLength) }}" min="40" max="255" required>
                    </div>
                </div>

                <div class="form-check form-switch mb-3">
                    <input type="checkbox" class="form-check-input" id="auto_sync" name="auto_sync" @checked(old('auto_sync', $autoSync))>
                    <label class="form-check-label" for="auto_sync">{{ trans('launcheraccess::admin.settings.auto_sync') }}</label>
                    <div class="form-text">{{ trans('launcheraccess::admin.settings.auto_sync_help') }}</div>
                </div>

                <button type="submit" class="btn btn-primary"><i class="bi bi-save"></i> {{ trans('messages.actions.save') }}</button>
            </form>
        </div>
    </div>

    <div class="card shadow mb-4">
        <div class="card-header"><strong>{{ trans('launcheraccess::admin.settings.sync_section') }}</strong></div>
        <div class="card-body">
            <p class="text-muted">{{ trans('launcheraccess::admin.settings.stats', [
                'profiles' => $profilesCount, 'mods' => $modsCount, 'rules' => $rulesCount, 'rows' => $managedCount,
            ]) }}</p>

            <form action="{{ route('launcheraccess.admin.settings.sync') }}" method="POST" class="d-flex gap-2">
                @csrf
                <button type="submit" name="mode" value="preview" class="btn btn-outline-secondary">
                    {{ trans('launcheraccess::admin.settings.preview_button') }}
                </button>
                <button type="submit" name="mode" value="apply" class="btn btn-warning">
                    {{ trans('launcheraccess::admin.settings.sync_button') }}
                </button>
            </form>
        </div>
    </div>

    <div class="card shadow mb-4">
        <div class="card-header"><strong>{{ trans('launcheraccess::admin.settings.catalog_section') }}</strong></div>
        <div class="card-body">
            <p class="text-muted">
                {{ trans('launcheraccess::admin.settings.catalog_help') }}
                @if($syncedAt)
                    <br>{{ trans('launcheraccess::admin.settings.synced_at', ['date' => $syncedAt]) }}
                @endif
            </p>

            <form action="{{ route('launcheraccess.admin.settings.import') }}" method="POST">
                @csrf
                <div class="mb-3">
                    <label class="form-label" for="catalog">{{ trans('launcheraccess::admin.settings.import_label') }}</label>
                    <textarea class="form-control font-monospace @error('catalog') is-invalid @enderror" id="catalog" name="catalog" rows="8"
                              placeholder='[{"uuid": "...", "title": "...", "limited": true, "optionals": [{"name": "Sodium", "limited": true}]}]'>{{ old('catalog') }}</textarea>
                    @error('catalog')<div class="invalid-feedback">{{ $message }}</div>@enderror
                </div>
                <button type="submit" class="btn btn-secondary"><i class="bi bi-cloud-download"></i> {{ trans('launcheraccess::admin.settings.import_button') }}</button>
            </form>
        </div>
    </div>
@endsection
