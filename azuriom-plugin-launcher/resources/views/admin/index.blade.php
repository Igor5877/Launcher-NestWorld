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

    @if($profiles->isEmpty())
        <div class="alert alert-info">
            <i class="bi bi-info-circle me-2"></i>
            Профілі ще не синхронізовані. Запустіть лаунчер-сервер — він автоматично заповнить список.
        </div>
    @else
        <div class="row g-4">
            @foreach($profiles as $p)
            <div class="col-12">
                <div class="card shadow-sm">
                    <div class="card-header d-flex justify-content-between align-items-center">
                        <div>
                            <span class="fw-semibold fs-5">{{ $p['name'] }}</span>
                            <span class="badge bg-secondary ms-2 font-monospace fw-normal">{{ $p['uuid'] }}</span>
                        </div>
                    </div>
                    <div class="card-body">

                        {{-- Поточний доступ --}}
                        @if($p['roles']->isNotEmpty() || $p['players']->isNotEmpty())
                        <div class="mb-3">
                            <div class="small fw-semibold text-uppercase text-muted mb-2">Поточний доступ</div>
                            <div class="d-flex flex-wrap gap-2">
                                @foreach($p['roles'] as $r)
                                    <span class="badge bg-primary d-flex align-items-center gap-1 py-2 px-3">
                                        <i class="bi bi-people-fill"></i>
                                        <span>{{ $r->subject_id }}</span>
                                        <form method="POST"
                                              action="{{ route('launcher.admin.access.revoke', $r->id) }}"
                                              class="d-inline ms-1">
                                            @csrf @method('DELETE')
                                            <button type="submit"
                                                    class="btn-close btn-close-white"
                                                    style="font-size:.55rem"
                                                    title="Відкликати доступ"></button>
                                        </form>
                                    </span>
                                @endforeach

                                @foreach($p['players'] as $pl)
                                    <span class="badge bg-success d-flex align-items-center gap-1 py-2 px-3">
                                        <i class="bi bi-person-fill"></i>
                                        <span>{{ $pl->display }}</span>
                                        <form method="POST"
                                              action="{{ route('launcher.admin.access.revoke', $pl->id) }}"
                                              class="d-inline ms-1">
                                            @csrf @method('DELETE')
                                            <button type="submit"
                                                    class="btn-close btn-close-white"
                                                    style="font-size:.55rem"
                                                    title="Відкликати доступ"></button>
                                        </form>
                                    </span>
                                @endforeach
                            </div>
                        </div>
                        @else
                            <p class="text-muted small mb-3">
                                <i class="bi bi-lock me-1"></i> Ніхто ще не має доступу до цього профілю.
                            </p>
                        @endif

                        {{-- Форми надання доступу --}}
                        <div class="row g-2">
                            <div class="col-sm-6">
                                <form method="POST" action="{{ route('launcher.admin.access.role') }}"
                                      class="border rounded p-3 bg-light h-100">
                                    @csrf
                                    <input type="hidden" name="profile_uuid" value="{{ $p['uuid'] }}">
                                    <div class="small fw-semibold mb-2">
                                        <i class="bi bi-people-fill text-primary"></i>
                                        Надати доступ ролі
                                    </div>
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

                            <div class="col-sm-6">
                                <form method="POST" action="{{ route('launcher.admin.access.player') }}"
                                      class="border rounded p-3 bg-light h-100">
                                    @csrf
                                    <input type="hidden" name="profile_uuid" value="{{ $p['uuid'] }}">
                                    <div class="small fw-semibold mb-2">
                                        <i class="bi bi-person-fill text-success"></i>
                                        Надати доступ гравцю
                                    </div>
                                    <div class="input-group input-group-sm">
                                        <input type="text" name="player_name"
                                               class="form-control"
                                               placeholder="Нікнейм гравця" required>
                                        <button class="btn btn-success">Надати</button>
                                    </div>
                                </form>
                            </div>
                        </div>

                    </div>
                </div>
            </div>
            @endforeach
        </div>
    @endif

</div>
@endsection
