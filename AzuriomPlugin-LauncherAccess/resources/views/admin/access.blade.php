@extends('admin.layouts.admin')

@section('title', trans('launcheraccess::admin.access.title'))

@section('content')
    @if($profiles->isEmpty())
        <div class="alert alert-warning">
            {{ trans('launcheraccess::admin.access.empty_catalog') }}
            <a href="{{ route('launcheraccess.admin.settings') }}">{{ trans('launcheraccess::admin.nav.settings') }}</a>
        </div>
    @endif

    <div class="card shadow mb-4">
        <div class="card-body">
            <label class="form-label" for="subject-input">{{ trans('launcheraccess::admin.access.who') }}</label>

            <div class="position-relative" id="subject-picker"
                 data-search-url="{{ route('launcheraccess.admin.subjects') }}"
                 data-page-url="{{ route('launcheraccess.admin.access') }}"
                 data-role-label="{{ trans('launcheraccess::admin.access.role') }}">
                <input type="text" class="form-control" id="subject-input" autocomplete="off"
                       placeholder="{{ trans('launcheraccess::admin.access.who_placeholder') }}"
                       value="{{ $subject['label'] ?? '' }}">
                <div class="list-group position-absolute w-100 shadow" id="subject-list"
                     style="z-index: 1050; max-height: 300px; overflow-y: auto; display: none;"></div>
            </div>

            <div class="form-text">{{ trans('launcheraccess::admin.access.who_help') }}</div>
        </div>
    </div>

    @if($subject !== null)
        @if($warning)
            <div class="alert alert-warning">{{ trans('launcheraccess::admin.access.no_game_id') }}</div>
        @endif

        @if($inherited['role'] !== null && ($inherited['profiles'] !== [] || $inherited['mods'] !== []))
            <div class="alert alert-info">
                {{ trans('launcheraccess::admin.access.via_role', ['role' => $inherited['role']]) }}
            </div>
        @endif

        <form action="{{ route('launcheraccess.admin.access.save') }}" method="POST">
            @csrf
            <input type="hidden" name="subject" value="{{ $subject['type'] }}:{{ $subject['model']->id }}">

            @foreach($profiles as $profile)
                @php
                    $profileSelected = in_array($profile->uuid, $selected['profiles'], true);
                    $profileInherited = in_array($profile->uuid, $inherited['profiles'], true);
                @endphp

                <div class="card shadow mb-3">
                    <div class="card-body">
                        <div class="form-check">
                            <input type="checkbox" class="form-check-input" name="profiles[]" value="{{ $profile->uuid }}"
                                   id="profile-{{ $profile->uuid }}" data-profile-box="{{ $profile->uuid }}"
                                   data-inherited="{{ $profileInherited ? '1' : '0' }}" data-public="{{ $profile->limited ? '0' : '1' }}" @checked($profileSelected)>
                            <label class="form-check-label fw-bold" for="profile-{{ $profile->uuid }}">{{ $profile->title }}</label>

                            @if($profile->limited)
                                <span class="badge bg-warning text-dark ms-2">{{ trans('launcheraccess::admin.access.limited') }}</span>
                            @else
                                <span class="badge bg-secondary ms-2">{{ trans('launcheraccess::admin.access.public') }}</span>
                            @endif

                            @if($profileInherited)
                                <span class="badge bg-info ms-1">{{ trans('launcheraccess::admin.access.inherited') }}</span>
                            @endif
                        </div>

                        @unless($profile->limited)
                            <div class="form-text ms-4">{{ trans('launcheraccess::admin.access.public_help') }}</div>
                        @endunless

                        @foreach($mods->get($profile->uuid, collect()) as $mod)
                            @php
                                $modValue = $profile->uuid.'|'.$mod->mod_key;
                                $modInherited = in_array($modValue, $inherited['mods'], true);
                            @endphp

                            <div class="form-check ms-4 mt-1">
                                <input type="checkbox" class="form-check-input" name="mods[]" value="{{ $modValue }}"
                                       id="mod-{{ $profile->uuid }}-{{ $loop->index }}" data-mod-of="{{ $profile->uuid }}"
                                       @checked(in_array($modValue, $selected['mods'], true))>
                                <label class="form-check-label" for="mod-{{ $profile->uuid }}-{{ $loop->index }}">{{ $mod->name }}</label>

                                @if($mod->limited)
                                    <span class="badge bg-warning text-dark ms-1">{{ trans('launcheraccess::admin.access.mod_limited') }}</span>
                                @else
                                    <span class="badge bg-secondary ms-1">{{ trans('launcheraccess::admin.access.public') }}</span>
                                @endif

                                @if($modInherited)
                                    <span class="badge bg-info ms-1">{{ trans('launcheraccess::admin.access.inherited') }}</span>
                                @endif
                            </div>
                        @endforeach
                    </div>
                </div>
            @endforeach

            @if($profiles->isNotEmpty())
                <div class="alert alert-secondary">{{ trans('launcheraccess::admin.access.mod_note') }}</div>

                <button type="submit" class="btn btn-primary">
                    <i class="bi bi-save"></i> {{ trans('messages.actions.save') }}
                </button>
            @endif
        </form>
    @endif
@endsection

@push('footer-scripts')
    <script>
        (function () {
            const picker = document.getElementById('subject-picker');
            const input = document.getElementById('subject-input');
            const list = document.getElementById('subject-list');
            let timer = null;
            let sequence = 0;

            function render(items) {
                list.textContent = '';

                items.forEach(function (item) {
                    const button = document.createElement('button');
                    button.type = 'button';
                    button.className = 'list-group-item list-group-item-action d-flex justify-content-between';

                    const name = document.createElement('span');
                    name.textContent = item.label;
                    button.appendChild(name);

                    if (item.type === 'role') {
                        const badge = document.createElement('span');
                        badge.className = 'badge bg-primary';
                        badge.textContent = picker.dataset.roleLabel;
                        button.appendChild(badge);
                    }

                    button.addEventListener('click', function () {
                        window.location.href = picker.dataset.pageUrl + '?subject=' + encodeURIComponent(item.id);
                    });

                    list.appendChild(button);
                });

                list.style.display = items.length ? 'block' : 'none';
            }

            function search() {
                const current = ++sequence;

                fetch(picker.dataset.searchUrl + '?q=' + encodeURIComponent(input.value.trim()), {
                    headers: {'Accept': 'application/json'}, credentials: 'same-origin'
                }).then(function (response) {
                    return response.json();
                }).then(function (items) {
                    // a slower answer to an older query must not replace the newer one
                    if (current === sequence) { render(items); }
                }).catch(function () {
                    if (current === sequence) { list.style.display = 'none'; }
                });
            }

            input.addEventListener('focus', function () { input.select(); search(); });
            input.addEventListener('input', function () {
                clearTimeout(timer);
                timer = setTimeout(search, 250);
            });
            document.addEventListener('click', function (event) {
                if (!picker.contains(event.target)) { list.style.display = 'none'; }
            });

            // a mod can only be allowed together with its profile
            function syncMods() {
                document.querySelectorAll('[data-mod-of]').forEach(function (mod) {
                    const box = document.querySelector('[data-profile-box="' + mod.dataset.modOf + '"]');
                    const allowed = box && (box.checked || box.dataset.inherited === '1' || box.dataset.public === '1');
                    mod.disabled = !allowed;
                    if (!allowed) { mod.checked = false; }
                });
            }

            document.querySelectorAll('[data-profile-box]').forEach(function (box) {
                box.addEventListener('change', syncMods);
            });
            syncMods();
        })();
    </script>
@endpush
