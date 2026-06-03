@extends('admin.layouts.admin')

@section('title', 'Launcher — Доступ до профілів')

@push('styles')
<style>
.stat-card { border: none; border-radius: 12px; }
.access-badge { font-size: .78rem; font-weight: 500; padding: .35rem .7rem; border-radius: 20px;
                display: inline-flex; align-items: center; gap: .35rem; }
.access-badge .revoke-btn { background: rgba(255,255,255,.25); border: none; border-radius: 50%;
                             width: 16px; height: 16px; display: flex; align-items: center;
                             justify-content: center; cursor: pointer; font-size: .6rem; color: #fff;
                             transition: background .15s; }
.access-badge .revoke-btn:hover { background: rgba(0,0,0,.2); }
.section-label { font-size: .68rem; font-weight: 700; text-transform: uppercase;
                 letter-spacing: .07em; color: #6c757d; margin-bottom: .5rem;
                 display: flex; align-items: center; gap: .35rem; }
.mod-accordion .accordion-item { border-radius: 8px !important; overflow: hidden;
                                  margin-bottom: 4px; border: 1px solid #e9ecef !important; }
.autocomplete-wrap { position: relative; }
.autocomplete-list { position: absolute; top: 100%; left: 0; right: 0; background: #fff;
                     border: 1px solid #dee2e6; border-radius: 0 0 8px 8px; z-index: 1050;
                     box-shadow: 0 4px 12px rgba(0,0,0,.12); max-height: 220px;
                     overflow-y: auto; display: none; }
.autocomplete-list.show { display: block; }
.autocomplete-item { padding: .4rem .75rem; cursor: pointer; display: flex;
                     align-items: center; gap: .5rem; font-size: .85rem; }
.autocomplete-item:hover { background: #f0f4ff; }
.autocomplete-item img { width: 24px; height: 24px; border-radius: 4px; }
</style>
@endpush

@section('content')
<div class="container-fluid">

    {{-- Summary bar --}}
    <div class="row g-3 mb-4">
        <div class="col-6 col-md-3">
            <div class="card stat-card text-center py-3 shadow-sm">
                <div class="display-6 fw-bold text-primary" id="stat-profiles">{{ $profiles->count() }}</div>
                <div class="small text-muted">Профілі</div>
            </div>
        </div>
        <div class="col-6 col-md-3">
            <div class="card stat-card text-center py-3 shadow-sm">
                <div class="display-6 fw-bold text-success" id="stat-players">—</div>
                <div class="small text-muted">Особистих доступів</div>
            </div>
        </div>
        <div class="col-6 col-md-3">
            <div class="card stat-card text-center py-3 shadow-sm">
                <div class="display-6 fw-bold text-info" id="stat-roles">—</div>
                <div class="small text-muted">Ролей з доступом</div>
            </div>
        </div>
        <div class="col-6 col-md-3">
            <div class="card stat-card text-center py-3 shadow-sm">
                <div class="display-6 fw-bold text-warning" id="stat-mods">—</div>
                <div class="small text-muted">Обмежених модів</div>
            </div>
        </div>
    </div>

    {{-- Filter --}}
    <div class="mb-3 d-flex align-items-center gap-2">
        <input type="search" class="form-control form-control-sm w-auto"
               placeholder="Фільтр профілів…"
               oninput="filterProfiles(this.value)">
        @if($profiles->isEmpty())
            <span class="badge bg-warning text-dark">
                <i class="bi bi-exclamation-triangle me-1"></i>
                Запустіть лаунчер-сервер для синхронізації профілів
            </span>
        @endif
    </div>

    {{-- Profiles --}}
    @foreach($profiles as $p)
    <div class="card shadow-sm mb-4 profile-card" data-name="{{ strtolower($p['name']) }}">
        <div class="card-header bg-white d-flex justify-content-between align-items-center py-3">
            <div class="d-flex align-items-center gap-2">
                <div class="fw-bold fs-5">{{ $p['name'] }}</div>
                <span class="badge bg-secondary font-monospace fw-normal" style="font-size:.65rem">{{ $p['uuid'] }}</span>
            </div>
            <div class="d-flex gap-2 flex-wrap justify-content-end">
                @if($p['roles']->isNotEmpty())
                    <span class="badge bg-primary-subtle text-primary border border-primary-subtle">
                        <i class="bi bi-people-fill me-1"></i>{{ $p['roles']->count() }} {{ trans_choice('роль|ролі|ролей', $p['roles']->count()) }}
                    </span>
                @endif
                @if($p['players']->isNotEmpty())
                    <span class="badge bg-success-subtle text-success border border-success-subtle">
                        <i class="bi bi-person-fill me-1"></i>{{ $p['players']->count() }} гравців
                    </span>
                @endif
                @if(count($p['mods']))
                    <span class="badge bg-warning-subtle text-warning border border-warning-subtle">
                        <i class="bi bi-puzzle-fill me-1"></i>{{ count($p['mods']) }} модів
                    </span>
                @endif
            </div>
        </div>
        <div class="card-body">

            {{-- Profile access --}}
            <div class="mb-4">
                <div class="section-label"><i class="bi bi-box-arrow-in-right"></i>Доступ до профілю</div>

                <div class="d-flex flex-wrap gap-2 mb-3 badge-container">
                    @forelse($p['roles'] as $r)
                        <span class="access-badge bg-primary text-white" data-id="{{ $r->id }}">
                            <i class="bi bi-people-fill"></i> {{ $r->subject_id }}
                            <button class="revoke-btn" onclick="confirmRevoke(this, {{ $r->id }}, '{{ $r->subject_id }}')" title="Відкликати">✕</button>
                        </span>
                    @empty
                    @endforelse
                    @foreach($p['players'] as $pl)
                        <span class="access-badge bg-success text-white" data-id="{{ $pl->id }}">
                            <i class="bi bi-person-fill"></i> {{ $pl->display }}
                            <button class="revoke-btn" onclick="confirmRevoke(this, {{ $pl->id }}, '{{ $pl->display }}')" title="Відкликати">✕</button>
                        </span>
                    @endforeach
                    @if($p['roles']->isEmpty() && $p['players']->isEmpty())
                        <span class="text-muted small badge-empty"><i class="bi bi-lock me-1"></i>Ніхто не має доступу.</span>
                    @endif
                </div>

                <div class="row g-2">
                    <div class="col-md-6">
                        <form method="POST" action="{{ route('launcher.admin.access.role') }}" class="border rounded-2 p-2 bg-light">
                            @csrf
                            <input type="hidden" name="profile_uuid" value="{{ $p['uuid'] }}">
                            <div class="input-group input-group-sm">
                                <select name="role_name" class="form-select" required>
                                    <option value="">— оберіть роль —</option>
                                    @foreach($allRoles as $role)
                                        <option value="{{ $role->name }}">{{ $role->name }}</option>
                                    @endforeach
                                </select>
                                <button class="btn btn-primary">Надати</button>
                            </div>
                        </form>
                    </div>
                    <div class="col-md-6">
                        <form method="POST" action="{{ route('launcher.admin.access.player') }}" class="border rounded-2 p-2 bg-light">
                            @csrf
                            <input type="hidden" name="profile_uuid" value="{{ $p['uuid'] }}">
                            <div class="input-group input-group-sm autocomplete-wrap">
                                <input type="text" name="player_name" class="form-control"
                                       placeholder="Нікнейм гравця…"
                                       oninput="searchPlayers(this, '{{ route('launcher.admin.search.users') }}')"
                                       autocomplete="off" required>
                                <button class="btn btn-success">Надати</button>
                                <div class="autocomplete-list"></div>
                            </div>
                        </form>
                    </div>
                </div>
            </div>

            {{-- Mods --}}
            @if(count($p['mods']))
            <div>
                <div class="section-label"><i class="bi bi-puzzle-fill"></i>Обмежені моди</div>
                <div class="mod-accordion accordion">
                    @foreach($p['mods'] as $mod)
                    <div class="accordion-item">
                        <h2 class="accordion-header">
                            <button class="accordion-button {{ ($mod['roles']->isEmpty() && $mod['players']->isEmpty()) ? 'collapsed' : '' }}"
                                    type="button" data-bs-toggle="collapse"
                                    data-bs-target="#mod-{{ Str::slug($p['uuid']) }}-{{ Str::slug($mod['name']) }}">
                                <span class="font-monospace me-2">{{ $mod['name'] }}</span>
                                @if($mod['info'])
                                    <span class="text-muted small me-auto">{{ $mod['info'] }}</span>
                                @else
                                    <span class="me-auto"></span>
                                @endif
                                @if($mod['roles']->isNotEmpty() || $mod['players']->isNotEmpty())
                                    <span class="badge bg-info-subtle text-info border border-info-subtle ms-2" style="font-size:.65rem">
                                        {{ $mod['roles']->count() + $mod['players']->count() }} доступів
                                    </span>
                                @else
                                    <span class="badge bg-secondary-subtle text-secondary ms-2" style="font-size:.65rem">немає доступу</span>
                                @endif
                            </button>
                        </h2>
                        <div id="mod-{{ Str::slug($p['uuid']) }}-{{ Str::slug($mod['name']) }}"
                             class="accordion-collapse collapse {{ ($mod['roles']->isNotEmpty() || $mod['players']->isNotEmpty()) ? 'show' : '' }}">
                            <div class="accordion-body pt-2 pb-3">

                                <div class="d-flex flex-wrap gap-2 mb-3 badge-container">
                                    @foreach($mod['roles'] as $r)
                                        <span class="access-badge bg-primary text-white" data-id="{{ $r->id }}">
                                            <i class="bi bi-people-fill"></i> {{ $r->subject_id }}
                                            <button class="revoke-btn" onclick="confirmRevoke(this, {{ $r->id }}, '{{ $r->subject_id }}')">✕</button>
                                        </span>
                                    @endforeach
                                    @foreach($mod['players'] as $pl)
                                        <span class="access-badge bg-success text-white" data-id="{{ $pl->id }}">
                                            <i class="bi bi-person-fill"></i> {{ $pl->display }}
                                            <button class="revoke-btn" onclick="confirmRevoke(this, {{ $pl->id }}, '{{ $pl->display }}')">✕</button>
                                        </span>
                                    @endforeach
                                    @if($mod['roles']->isEmpty() && $mod['players']->isEmpty())
                                        <span class="text-muted small badge-empty"><i class="bi bi-lock me-1"></i>Ніхто не має доступу.</span>
                                    @endif
                                </div>

                                <div class="row g-2">
                                    <div class="col-md-6">
                                        <form method="POST" action="{{ route('launcher.admin.access.role') }}" class="d-flex gap-1">
                                            @csrf
                                            <input type="hidden" name="profile_uuid" value="{{ $p['uuid'] }}">
                                            <input type="hidden" name="mod_name" value="{{ $mod['name'] }}">
                                            <select name="role_name" class="form-select form-select-sm" required>
                                                <option value="">— роль —</option>
                                                @foreach($allRoles as $role)
                                                    <option value="{{ $role->name }}">{{ $role->name }}</option>
                                                @endforeach
                                            </select>
                                            <button class="btn btn-primary btn-sm text-nowrap">Надати</button>
                                        </form>
                                    </div>
                                    <div class="col-md-6">
                                        <form method="POST" action="{{ route('launcher.admin.access.player') }}" class="d-flex gap-1">
                                            @csrf
                                            <input type="hidden" name="profile_uuid" value="{{ $p['uuid'] }}">
                                            <input type="hidden" name="mod_name" value="{{ $mod['name'] }}">
                                            <div class="input-group input-group-sm autocomplete-wrap flex-grow-1">
                                                <input type="text" name="player_name" class="form-control"
                                                       placeholder="Нікнейм…"
                                                       oninput="searchPlayers(this, '{{ route('launcher.admin.search.users') }}')"
                                                       autocomplete="off" required>
                                                <button class="btn btn-success btn-sm text-nowrap">Надати</button>
                                                <div class="autocomplete-list"></div>
                                            </div>
                                        </form>
                                    </div>
                                </div>
                            </div>
                        </div>
                    </div>
                    @endforeach
                </div>
            </div>
            @endif

        </div>
    </div>
    @endforeach

</div>

{{-- Confirm modal --}}
<div class="modal fade" id="confirmModal" tabindex="-1">
    <div class="modal-dialog modal-dialog-centered modal-sm">
        <div class="modal-content">
            <div class="modal-body text-center py-4">
                <i class="bi bi-exclamation-triangle-fill text-warning fs-2 mb-3 d-block"></i>
                <div class="fw-semibold mb-1">Відкликати доступ?</div>
                <p class="text-muted small mb-0" id="confirmText"></p>
            </div>
            <div class="modal-footer border-0 pt-0 justify-content-center gap-2">
                <button class="btn btn-light btn-sm px-4" data-bs-dismiss="modal">Скасувати</button>
                <button class="btn btn-danger btn-sm px-4" id="confirmBtn">Відкликати</button>
            </div>
        </div>
    </div>
</div>

{{-- Toast --}}
<div class="position-fixed bottom-0 end-0 p-3" style="z-index:9999">
    <div id="mainToast" class="toast align-items-center border-0" role="alert">
        <div class="d-flex">
            <div class="toast-body" id="toastMsg"></div>
            <button type="button" class="btn-close me-2 m-auto" data-bs-dismiss="toast"></button>
        </div>
    </div>
</div>
@endsection

@push('scripts')
<script>
const REVOKE_URL = "{{ route('launcher.admin.access.revoke', ['id' => '__ID__']) }}";
const CSRF = "{{ csrf_token() }}";

// ── Stats ─────────────────────────────────────────────────────
fetch("{{ route('launcher.admin.stats') }}")
    .then(r => r.json())
    .then(d => {
        document.getElementById('stat-players').textContent = d.playerGrants;
        document.getElementById('stat-roles').textContent   = d.roleMappings;
        document.getElementById('stat-mods').textContent    = d.limitedMods;
    });

// ── Profile filter ────────────────────────────────────────────
function filterProfiles(q) {
    document.querySelectorAll('.profile-card').forEach(c => {
        c.style.display = c.dataset.name.includes(q.toLowerCase()) ? '' : 'none';
    });
}

// ── Autocomplete ──────────────────────────────────────────────
const acCache = {};
function searchPlayers(input, url) {
    const q = input.value.trim();
    const list = input.closest('.autocomplete-wrap').querySelector('.autocomplete-list');
    if (q.length < 2) { list.classList.remove('show'); return; }
    if (acCache[q]) { renderAc(list, acCache[q], input); return; }
    fetch(`${url}?q=${encodeURIComponent(q)}`)
        .then(r => r.json())
        .then(users => { acCache[q] = users; renderAc(list, users, input); });
}
function renderAc(list, users, input) {
    if (!users.length) { list.classList.remove('show'); return; }
    list.innerHTML = users.map(u =>
        `<div class="autocomplete-item" onclick="pickPlayer(this)">
            <img src="https://cravatar.eu/helmavatar/${encodeURIComponent(u.name)}/24"
                 onerror="this.src='https://ui-avatars.com/api/?name=${u.name[0]}&size=24&background=6c757d&color=fff'">
            <span>${u.name}</span>
         </div>`
    ).join('');
    list.classList.add('show');
}
function pickPlayer(el) {
    const wrap = el.closest('.autocomplete-wrap');
    wrap.querySelector('input').value = el.querySelector('span').textContent;
    wrap.querySelector('.autocomplete-list').classList.remove('show');
}
document.addEventListener('click', e => {
    if (!e.target.closest('.autocomplete-wrap'))
        document.querySelectorAll('.autocomplete-list').forEach(l => l.classList.remove('show'));
});

// ── Revoke ────────────────────────────────────────────────────
let pendingId = null, pendingBadge = null;
function confirmRevoke(btn, id, name) {
    pendingId    = id;
    pendingBadge = btn.closest('.access-badge');
    document.getElementById('confirmText').textContent = `"${name}" більше не матиме доступу.`;
    new bootstrap.Modal('#confirmModal').show();
}
document.getElementById('confirmBtn').addEventListener('click', async () => {
    bootstrap.Modal.getInstance('#confirmModal').hide();
    if (!pendingId) return;
    const url = REVOKE_URL.replace('__ID__', pendingId);
    const res = await fetch(url, {
        method: 'POST',
        headers: { 'X-CSRF-TOKEN': CSRF, 'Content-Type': 'application/x-www-form-urlencoded' },
        body: '_method=DELETE'
    });
    if (res.ok) {
        pendingBadge.style.transition = 'opacity .2s, transform .2s';
        pendingBadge.style.opacity = '0';
        pendingBadge.style.transform = 'scale(.8)';
        setTimeout(() => {
            const container = pendingBadge.closest('.badge-container');
            pendingBadge.remove();
            if (!container.querySelector('.access-badge')) {
                container.innerHTML = '<span class="text-muted small badge-empty"><i class="bi bi-lock me-1"></i>Ніхто не має доступу.</span>';
            }
        }, 200);
        showToast('Доступ відкликано.', 'success');
    } else {
        showToast('Помилка при відкликанні.', 'danger');
    }
});

// ── Toast ─────────────────────────────────────────────────────
function showToast(msg, type = 'success') {
    const el = document.getElementById('mainToast');
    el.className = `toast align-items-center text-bg-${type} border-0`;
    document.getElementById('toastMsg').innerHTML =
        `<i class="bi bi-${type === 'success' ? 'check' : 'x'}-circle me-2"></i>${msg}`;
    new bootstrap.Toast(el, { delay: 3000 }).show();
}
</script>
@endpush
