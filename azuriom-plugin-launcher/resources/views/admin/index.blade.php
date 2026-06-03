@extends('admin.layouts.admin')

@section('title', 'Launcher — Доступ до профілів')

@section('content')
<div class="container-fluid">

    @if(session('success'))
        <div class="alert alert-success alert-dismissible fade show">
            {{ session('success') }}
            <button type="button" class="btn-close" data-bs-dismiss="alert"></button>
        </div>
    @endif
    @if($errors->any())
        <div class="alert alert-danger alert-dismissible fade show">
            @foreach($errors->all() as $e)<div>{{ $e }}</div>@endforeach
            <button type="button" class="btn-close" data-bs-dismiss="alert"></button>
        </div>
    @endif

    @if($profiles->isEmpty())
        <div class="alert alert-info">
            <i class="bi bi-info-circle me-2"></i>
            Профілів ще немає — запустіть лаунчер-сервер, він заповнить список автоматично.
        </div>
    @endif

    @foreach($profiles as $p)
    <div class="card shadow-sm mb-4">
        <div class="card-header d-flex justify-content-between align-items-center py-3">
            <span class="fw-bold fs-5">{{ $p['name'] }}</span>
            <span class="badge bg-secondary font-monospace fw-normal">{{ $p['uuid'] }}</span>
        </div>
        <div class="card-body">

            {{-- ===== ДОСТУП ДО ПРОФІЛЮ ===== --}}
            <div class="mb-4">
                <div class="small fw-semibold text-uppercase text-muted mb-2">
                    <i class="bi bi-box-arrow-in-right me-1"></i>Доступ до профілю
                </div>

                @if(count($p['roles']) || count($p['players']))
                    <div class="d-flex flex-wrap gap-2 mb-2">
                        @foreach($p['roles'] as $r)
                            <span class="badge bg-primary d-flex align-items-center gap-1 py-2 px-3">
                                <i class="bi bi-people-fill"></i> {{ $r->subject_id }}
                                <form method="POST" action="{{ route('launcher.admin.access.revoke', $r->id) }}" class="d-inline ms-1">
                                    @csrf @method('DELETE')
                                    <button class="btn-close btn-close-white" style="font-size:.55rem" title="Відкликати"></button>
                                </form>
                            </span>
                        @endforeach
                        @foreach($p['players'] as $pl)
                            <span class="badge bg-success d-flex align-items-center gap-1 py-2 px-3">
                                <i class="bi bi-person-fill"></i> {{ $pl->display }}
                                <form method="POST" action="{{ route('launcher.admin.access.revoke', $pl->id) }}" class="d-inline ms-1">
                                    @csrf @method('DELETE')
                                    <button class="btn-close btn-close-white" style="font-size:.55rem" title="Відкликати"></button>
                                </form>
                            </span>
                        @endforeach
                    </div>
                @else
                    <p class="text-muted small mb-2"><i class="bi bi-lock me-1"></i>Ніхто не має доступу.</p>
                @endif

                <div class="row g-2">
                    <div class="col-sm-6">
                        <form method="POST" action="{{ route('launcher.admin.access.role') }}" class="border rounded p-2 bg-light">
                            @csrf
                            <input type="hidden" name="profile_uuid" value="{{ $p['uuid'] }}">
                            <div class="input-group input-group-sm">
                                <select name="role_name" class="form-select" required>
                                    <option value="">— роль —</option>
                                    @foreach($allRoles as $role)
                                        <option value="{{ $role->name }}">{{ $role->name }}</option>
                                    @endforeach
                                </select>
                                <button class="btn btn-primary btn-sm">Надати ролі</button>
                            </div>
                        </form>
                    </div>
                    <div class="col-sm-6">
                        <form method="POST" action="{{ route('launcher.admin.access.player') }}" class="border rounded p-2 bg-light">
                            @csrf
                            <input type="hidden" name="profile_uuid" value="{{ $p['uuid'] }}">
                            <div class="input-group input-group-sm">
                                <input type="text" name="player_name" class="form-control" placeholder="Нікнейм гравця" required>
                                <button class="btn btn-success btn-sm">Надати гравцю</button>
                            </div>
                        </form>
                    </div>
                </div>
            </div>

            {{-- ===== МОДИ (тільки limited:true) ===== --}}
            @if(count($p['mods']))
            <div>
                <div class="small fw-semibold text-uppercase text-muted mb-2">
                    <i class="bi bi-puzzle-fill me-1"></i>Обмежені моди
                </div>

                <div class="accordion" id="mods-{{ Str::slug($p['uuid']) }}">
                    @foreach($p['mods'] as $mod)
                    <div class="accordion-item border">
                        <h2 class="accordion-header">
                            <button class="accordion-button collapsed py-2" type="button"
                                    data-bs-toggle="collapse"
                                    data-bs-target="#mod-{{ Str::slug($p['uuid']) }}-{{ Str::slug($mod['name']) }}">
                                <span class="font-monospace me-2">{{ $mod['name'] }}</span>
                                @if(count($mod['roles']) || count($mod['players']))
                                    <span class="badge bg-info text-dark ms-1">
                                        {{ count($mod['roles']) + count($mod['players']) }} доступ(ів)
                                    </span>
                                @else
                                    <span class="badge bg-secondary ms-1">немає доступу</span>
                                @endif
                            </button>
                        </h2>
                        <div id="mod-{{ Str::slug($p['uuid']) }}-{{ Str::slug($mod['name']) }}"
                             class="accordion-collapse collapse">
                            <div class="accordion-body pt-2">

                                @if($mod['info'])
                                    <p class="text-muted small mb-2">{{ $mod['info'] }}</p>
                                @endif

                                @if(count($mod['roles']) || count($mod['players']))
                                    <div class="d-flex flex-wrap gap-2 mb-2">
                                        @foreach($mod['roles'] as $r)
                                            <span class="badge bg-primary d-flex align-items-center gap-1 py-1 px-2">
                                                <i class="bi bi-people-fill"></i> {{ $r->subject_id }}
                                                <form method="POST" action="{{ route('launcher.admin.access.revoke', $r->id) }}" class="d-inline ms-1">
                                                    @csrf @method('DELETE')
                                                    <button class="btn-close btn-close-white" style="font-size:.5rem"></button>
                                                </form>
                                            </span>
                                        @endforeach
                                        @foreach($mod['players'] as $pl)
                                            <span class="badge bg-success d-flex align-items-center gap-1 py-1 px-2">
                                                <i class="bi bi-person-fill"></i> {{ $pl->display }}
                                                <form method="POST" action="{{ route('launcher.admin.access.revoke', $pl->id) }}" class="d-inline ms-1">
                                                    @csrf @method('DELETE')
                                                    <button class="btn-close btn-close-white" style="font-size:.5rem"></button>
                                                </form>
                                            </span>
                                        @endforeach
                                    </div>
                                @endif

                                <div class="row g-2">
                                    <div class="col-sm-6">
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
                                            <button class="btn btn-primary btn-sm text-nowrap">Надати ролі</button>
                                        </form>
                                    </div>
                                    <div class="col-sm-6">
                                        <form method="POST" action="{{ route('launcher.admin.access.player') }}" class="d-flex gap-1">
                                            @csrf
                                            <input type="hidden" name="profile_uuid" value="{{ $p['uuid'] }}">
                                            <input type="hidden" name="mod_name" value="{{ $mod['name'] }}">
                                            <input type="text" name="player_name" class="form-control form-control-sm" placeholder="Нікнейм" required>
                                            <button class="btn btn-success btn-sm text-nowrap">Надати гравцю</button>
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
@endsection
