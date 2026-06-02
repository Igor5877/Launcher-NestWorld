@extends('admin.layouts.admin')

@section('title', 'Launcher — Доступ до профілів')

@section('content')
<div class="container-fluid">

    {{-- Повідомлення --}}
    @if(session('success'))
        <div class="alert alert-success alert-dismissible fade show">
            {{ session('success') }}
            <button type="button" class="btn-close" data-bs-dismiss="alert"></button>
        </div>
    @endif
    @if($errors->any())
        <div class="alert alert-danger alert-dismissible fade show">
            @foreach($errors->all() as $e) <div>{{ $e }}</div> @endforeach
            <button type="button" class="btn-close" data-bs-dismiss="alert"></button>
        </div>
    @endif

    <div class="row">

        {{-- ===== ЛІВА КОЛОНКА: список профілів ===== --}}
        <div class="col-lg-4">
            <div class="card shadow-sm mb-4">
                <div class="card-header fw-semibold">Профілі лаунчера</div>
                <div class="card-body">
                    @forelse($profiles as $p)
                        <div class="d-flex justify-content-between align-items-start border-bottom py-2">
                            <div>
                                <div class="fw-semibold">{{ $p['name'] }}</div>
                                <small class="text-muted font-monospace">{{ $p['uuid'] }}</small>
                                @if($p['description'])
                                    <div><small class="text-secondary">{{ $p['description'] }}</small></div>
                                @endif
                            </div>
                            <form method="POST"
                                  action="{{ route('launcher.admin.profiles.delete', $p['uuid']) }}"
                                  onsubmit="return confirm('Видалити профіль та весь його доступ?')">
                                @csrf @method('DELETE')
                                <button class="btn btn-sm btn-outline-danger">✕</button>
                            </form>
                        </div>
                    @empty
                        <p class="text-muted mb-0">Профілів ще немає.</p>
                    @endforelse
                </div>
            </div>

            {{-- Додати профіль --}}
            <div class="card shadow-sm">
                <div class="card-header fw-semibold">Додати профіль</div>
                <div class="card-body">
                    <form method="POST" action="{{ route('launcher.admin.profiles.create') }}">
                        @csrf
                        <div class="mb-2">
                            <label class="form-label small">UUID профілю</label>
                            <input type="text" name="uuid" class="form-control form-control-sm font-monospace"
                                   placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
                                   value="{{ old('uuid') }}" required>
                        </div>
                        <div class="mb-2">
                            <label class="form-label small">Назва</label>
                            <input type="text" name="name" class="form-control form-control-sm"
                                   placeholder="Survival, VIP, Creative…"
                                   value="{{ old('name') }}" required>
                        </div>
                        <div class="mb-3">
                            <label class="form-label small">Опис (необов'язково)</label>
                            <input type="text" name="description" class="form-control form-control-sm"
                                   value="{{ old('description') }}">
                        </div>
                        <button class="btn btn-primary btn-sm w-100">Додати профіль</button>
                    </form>
                </div>
            </div>
        </div>

        {{-- ===== ПРАВА КОЛОНКА: управління доступом ===== --}}
        <div class="col-lg-8">

            @forelse($profiles as $p)
            <div class="card shadow-sm mb-4">
                <div class="card-header d-flex justify-content-between align-items-center">
                    <span class="fw-semibold">{{ $p['name'] }}</span>
                    <small class="text-muted font-monospace">{{ $p['uuid'] }}</small>
                </div>
                <div class="card-body">

                    {{-- Поточний доступ --}}
                    @if($p['roles']->isNotEmpty() || $p['players']->isNotEmpty())
                    <div class="mb-3">
                        <div class="small fw-semibold text-uppercase text-muted mb-1">Поточний доступ</div>
                        <div class="d-flex flex-wrap gap-2">
                            @foreach($p['roles'] as $r)
                                <span class="badge bg-primary d-flex align-items-center gap-1">
                                    <i class="bi bi-people-fill"></i> {{ $r->subject_id }}
                                    <form method="POST"
                                          action="{{ route('launcher.admin.access.revoke', $r->id) }}"
                                          class="d-inline ms-1">
                                        @csrf @method('DELETE')
                                        <button type="submit" class="btn-close btn-close-white"
                                                style="font-size:.6rem"
                                                title="Відкликати"></button>
                                    </form>
                                </span>
                            @endforeach
                            @foreach($p['players'] as $pl)
                                <span class="badge bg-success d-flex align-items-center gap-1">
                                    <i class="bi bi-person-fill"></i> {{ $pl->display }}
                                    <form method="POST"
                                          action="{{ route('launcher.admin.access.revoke', $pl->id) }}"
                                          class="d-inline ms-1">
                                        @csrf @method('DELETE')
                                        <button type="submit" class="btn-close btn-close-white"
                                                style="font-size:.6rem"
                                                title="Відкликати"></button>
                                    </form>
                                </span>
                            @endforeach
                        </div>
                    </div>
                    @else
                        <p class="text-muted small">Ніхто ще не має доступу до цього профілю.</p>
                    @endif

                    <div class="row g-2">
                        {{-- Додати роль --}}
                        <div class="col-sm-6">
                            <form method="POST" action="{{ route('launcher.admin.access.role') }}"
                                  class="border rounded p-2 bg-light">
                                @csrf
                                <input type="hidden" name="profile_uuid" value="{{ $p['uuid'] }}">
                                <div class="small fw-semibold mb-1">
                                    <i class="bi bi-people-fill text-primary"></i> Дати доступ ролі
                                </div>
                                <div class="input-group input-group-sm">
                                    <select name="role_name" class="form-select form-select-sm" required>
                                        <option value="">— оберіть роль —</option>
                                        @foreach($allRoles as $role)
                                            <option value="{{ $role->name }}">{{ $role->name }}</option>
                                        @endforeach
                                    </select>
                                    <button class="btn btn-primary btn-sm">Дати</button>
                                </div>
                            </form>
                        </div>

                        {{-- Додати гравця --}}
                        <div class="col-sm-6">
                            <form method="POST" action="{{ route('launcher.admin.access.player') }}"
                                  class="border rounded p-2 bg-light">
                                @csrf
                                <input type="hidden" name="profile_uuid" value="{{ $p['uuid'] }}">
                                <div class="small fw-semibold mb-1">
                                    <i class="bi bi-person-fill text-success"></i> Дати доступ гравцю
                                </div>
                                <div class="input-group input-group-sm">
                                    <input type="text" name="player_name" class="form-control form-control-sm"
                                           placeholder="Нікнейм гравця" required>
                                    <button class="btn btn-success btn-sm">Дати</button>
                                </div>
                            </form>
                        </div>
                    </div>

                </div>
            </div>
            @empty
                <div class="alert alert-info">Спочатку додайте профілі зліва.</div>
            @endforelse

        </div>
    </div>
</div>
@endsection
