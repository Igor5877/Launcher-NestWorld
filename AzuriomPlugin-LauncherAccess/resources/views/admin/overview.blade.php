@extends('admin.layouts.admin')

@section('title', trans('launcheraccess::admin.overview.title'))

@section('content')
    @if($profile === null)
        <div class="alert alert-warning">{{ trans('launcheraccess::admin.access.empty_catalog') }}</div>
    @else
        <div class="card shadow mb-4">
            <div class="card-body">
                <form action="{{ route('launcheraccess.admin.overview') }}" method="GET" class="row g-2 align-items-end">
                    <div class="col-md-6">
                        <label class="form-label" for="profile">{{ trans('launcheraccess::admin.overview.profile') }}</label>
                        <select class="form-select" id="profile" name="profile" onchange="this.form.submit()">
                            @foreach($profiles as $item)
                                <option value="{{ $item->uuid }}" @selected($item->uuid === $profile->uuid)>{{ $item->title }}</option>
                            @endforeach
                        </select>
                    </div>
                </form>
            </div>
        </div>

        @foreach($sections as $section)
            <div class="card shadow mb-3">
                <div class="card-header">
                    <strong>{{ $section['title'] }}</strong>
                    @if($section['limited'])
                        <span class="badge bg-warning text-dark ms-2">{{ trans('launcheraccess::admin.access.limited') }}</span>
                    @else
                        <span class="badge bg-secondary ms-2">{{ trans('launcheraccess::admin.overview.everyone') }}</span>
                    @endif
                </div>
                <div class="card-body">
                    @if(! $section['limited'])
                        <p class="text-muted mb-2">{{ trans('launcheraccess::admin.access.public_help') }}</p>
                    @endif

                    @if($section['users'] === [] && $section['roles'] === [])
                        <p class="mb-0 text-muted">{{ trans('launcheraccess::admin.overview.nobody') }}</p>
                    @endif

                    @if($section['roles'] !== [])
                        <h6>{{ trans('launcheraccess::admin.overview.roles') }}</h6>
                        <ul>
                            @foreach($section['roles'] as $role)
                                <li>
                                    <strong>{{ $role['name'] }}</strong>
                                    ({{ trans('launcheraccess::admin.overview.members', ['count' => $role['members']]) }})
                                    @if($role['names'] !== [])
                                        <span class="text-muted">: {{ implode(', ', $role['names']) }}@if($role['members'] > count($role['names'])) …@endif</span>
                                    @endif
                                </li>
                            @endforeach
                        </ul>
                    @endif

                    @if($section['users'] !== [])
                        <h6>{{ trans('launcheraccess::admin.overview.users') }}</h6>
                        <p class="mb-0">{{ implode(', ', $section['users']) }}</p>
                    @endif
                </div>
            </div>
        @endforeach
    @endif
@endsection
