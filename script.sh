#!/usr/bin/env bash
set -euo pipefail

APP_ID="com.cirabit.android"
MAIN_ACTIVITY="${APP_ID}/com.cirabit.android.MainActivity"
COMPILE_SDK="34"

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
PROJECT_ROOT="$SCRIPT_DIR"
GRADLEW="$PROJECT_ROOT/gradlew"
LOCAL_PROPERTIES_FILE="$PROJECT_ROOT/local.properties"

DEVICE_SERIAL=""

log() {
  printf '[cirabit] %s\n' "$*"
}

error() {
  printf '[cirabit] ERRO: %s\n' "$*" >&2
}

usage() {
  cat <<'EOF'
Uso:
  ./script.sh [opcoes] <comando> [buildType]

Opcoes:
  -d, --device <serial>   Serial do dispositivo/emulador (adb).
  -h, --help              Mostra esta ajuda.

Comandos:
  build [debug|release]       Gera APK (padrao: debug).
  bundle                      Gera AAB release.
  test                        Roda testes unitarios.
  test-android                Roda testes instrumentados (device/emulador).
  lint                        Roda Android Lint.
  doctor                      Diagnostica SDK Android/adb.
  install [debug|release]     Builda e instala APK no device/emulador.
  run [debug|release]         Instala e abre o app no device/emulador.
  uninstall                   Remove o app do device/emulador.
  clean                       Limpa artefatos de build.
  devices                     Lista dispositivos adb.
  logs                        Mostra logcat (usa PID do app se estiver aberto).
  apk-path [debug|release]    Mostra caminho do APK gerado.
  all                         clean + test + build debug + install debug.

Exemplos:
  ./script.sh build
  ./script.sh test
  ./script.sh -d emulator-5554 run
EOF
}

require_gradle() {
  if [[ ! -x "$GRADLEW" ]]; then
    error "Gradle wrapper nao encontrado ou sem permissao de execucao: $GRADLEW"
    exit 1
  fi
}

require_adb() {
  if ! command -v adb >/dev/null 2>&1; then
    error "adb nao encontrado no PATH. Instale Android Platform Tools."
    exit 1
  fi
}

read_local_sdk_dir() {
  local line=""
  if [[ ! -f "$LOCAL_PROPERTIES_FILE" ]]; then
    return 0
  fi

  line="$(grep -E '^sdk\.dir=' "$LOCAL_PROPERTIES_FILE" | head -n 1 || true)"
  if [[ -z "$line" ]]; then
    return 0
  fi

  line="${line#sdk.dir=}"
  line="${line//\\:/:}"
  line="${line//\\\\/\\}"
  printf '%s\n' "$line"
}

detect_sdk_dir() {
  local from_local
  local candidates
  local candidate

  from_local="$(read_local_sdk_dir || true)"
  candidates=(
    "$from_local"
    "${ANDROID_HOME-}"
    "${ANDROID_SDK_ROOT-}"
    "$HOME/Android/Sdk"
    "$HOME/Library/Android/sdk"
    "/usr/lib/android-sdk"
    "/opt/android-sdk"
    "/usr/local/android-sdk"
  )

  for candidate in "${candidates[@]}"; do
    [[ -n "$candidate" ]] || continue
    if [[ -d "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done

  return 1
}

set_local_sdk_dir() {
  local sdk_dir="$1"
  local tmp_file="${LOCAL_PROPERTIES_FILE}.tmp"

  if [[ -f "$LOCAL_PROPERTIES_FILE" ]]; then
    if grep -q '^sdk\.dir=' "$LOCAL_PROPERTIES_FILE"; then
      awk -v value="$sdk_dir" '
        BEGIN { replaced = 0 }
        /^sdk\.dir=/ {
          if (!replaced) {
            print "sdk.dir=" value
            replaced = 1
          }
          next
        }
        { print }
        END {
          if (!replaced) print "sdk.dir=" value
        }
      ' "$LOCAL_PROPERTIES_FILE" > "$tmp_file"
      mv "$tmp_file" "$LOCAL_PROPERTIES_FILE"
    else
      printf '\nsdk.dir=%s\n' "$sdk_dir" >> "$LOCAL_PROPERTIES_FILE"
    fi
  else
    printf 'sdk.dir=%s\n' "$sdk_dir" > "$LOCAL_PROPERTIES_FILE"
  fi
}

missing_sdk_components() {
  local sdk_dir="$1"
  local build_tools_variant=""
  local missing=()

  if [[ ! -d "$sdk_dir/platforms/android-$COMPILE_SDK" ]]; then
    missing+=("platforms/android-$COMPILE_SDK")
  fi

  if [[ -d "$sdk_dir/build-tools" ]]; then
    build_tools_variant="$(find "$sdk_dir/build-tools" -mindepth 1 -maxdepth 1 -type d | head -n 1 || true)"
  fi
  if [[ -z "$build_tools_variant" ]]; then
    missing+=("build-tools/<versao>")
  fi

  if [[ ! -d "$sdk_dir/platform-tools" ]]; then
    missing+=("platform-tools")
  fi

  if [[ ${#missing[@]} -gt 0 ]]; then
    printf '%s\n' "${missing[@]}"
    return 1
  fi

  return 0
}

print_sdk_install_hint() {
  cat >&2 <<EOF
[cirabit] Para corrigir:
[cirabit] 1) Instale Android SDK Platform $COMPILE_SDK e Build-Tools pelo Android Studio (SDK Manager), ou via sdkmanager.
[cirabit] 2) Garanta que o path do SDK esteja em ANDROID_HOME/ANDROID_SDK_ROOT.
[cirabit] 3) Rode novamente: ./script.sh doctor
EOF

  if command -v sdkmanager >/dev/null 2>&1; then
    cat >&2 <<EOF
[cirabit] Exemplo com sdkmanager:
[cirabit] sdkmanager "platform-tools" "platforms;android-$COMPILE_SDK" "build-tools;34.0.0"
EOF
  fi
}

ensure_android_sdk_ready() {
  local sdk_dir
  local local_sdk
  local missing

  local_sdk="$(read_local_sdk_dir || true)"
  if ! sdk_dir="$(detect_sdk_dir)"; then
    error "SDK Android nao encontrado."
    cat >&2 <<EOF
[cirabit] Defina ANDROID_HOME/ANDROID_SDK_ROOT ou crie $LOCAL_PROPERTIES_FILE com:
[cirabit] sdk.dir=/caminho/do/android-sdk
EOF
    print_sdk_install_hint
    exit 1
  fi

  export ANDROID_HOME="$sdk_dir"
  export ANDROID_SDK_ROOT="$sdk_dir"

  if [[ "$local_sdk" != "$sdk_dir" ]]; then
    set_local_sdk_dir "$sdk_dir"
    log "Atualizado local.properties com sdk.dir=$sdk_dir"
  fi

  mapfile -t missing < <(missing_sdk_components "$sdk_dir" || true)
  if [[ ${#missing[@]} -gt 0 ]]; then
    error "SDK encontrado em '$sdk_dir', mas faltam componentes: ${missing[*]}"
    print_sdk_install_hint
    exit 1
  fi
}

doctor() {
  local sdk_dir
  local local_sdk
  local missing
  local status=0

  local_sdk="$(read_local_sdk_dir || true)"

  printf 'Projeto: %s\n' "$PROJECT_ROOT"
  printf 'ANDROID_HOME=%s\n' "${ANDROID_HOME-}"
  printf 'ANDROID_SDK_ROOT=%s\n' "${ANDROID_SDK_ROOT-}"
  printf 'local.properties sdk.dir=%s\n' "${local_sdk:-<nao definido>}"

  if sdk_dir="$(detect_sdk_dir || true)"; then
    printf 'SDK detectado: %s\n' "$sdk_dir"
    mapfile -t missing < <(missing_sdk_components "$sdk_dir" || true)
    if [[ ${#missing[@]} -eq 0 ]]; then
      printf 'SDK status: OK (componentes principais presentes)\n'
    else
      printf 'SDK status: FALTANDO %s\n' "${missing[*]}"
      status=1
    fi
  else
    printf 'SDK status: NAO ENCONTRADO\n'
    status=1
  fi

  if command -v adb >/dev/null 2>&1; then
    printf 'adb: %s\n' "$(adb version | head -n 1)"
    printf 'Dispositivos online:\n'
    adb devices | sed '1d' | sed '/^$/d' || true
  else
    printf 'adb: NAO ENCONTRADO\n'
    status=1
  fi

  if [[ "$status" -ne 0 ]]; then
    print_sdk_install_hint
    return 1
  fi
}

adb_cmd() {
  if [[ -n "$DEVICE_SERIAL" ]]; then
    adb -s "$DEVICE_SERIAL" "$@"
  else
    adb "$@"
  fi
}

list_connected_devices() {
  adb devices | awk 'NR > 1 && $2 == "device" {print $1}'
}

ensure_device_ready() {
  require_adb
  mapfile -t devices < <(list_connected_devices)

  if [[ ${#devices[@]} -eq 0 ]]; then
    error "Nenhum device/emulador conectado."
    exit 1
  fi

  if [[ -n "$DEVICE_SERIAL" ]]; then
    local found=0
    local d
    for d in "${devices[@]}"; do
      if [[ "$d" == "$DEVICE_SERIAL" ]]; then
        found=1
        break
      fi
    done

    if [[ "$found" -ne 1 ]]; then
      error "Serial '$DEVICE_SERIAL' nao encontrado entre dispositivos online: ${devices[*]}"
      exit 1
    fi
  elif [[ ${#devices[@]} -gt 1 ]]; then
    error "Mais de um device conectado (${devices[*]}). Use -d <serial>."
    exit 1
  fi
}

normalize_build_type() {
  local build_type="${1:-debug}"
  build_type="${build_type,,}"
  case "$build_type" in
    debug|release) printf '%s\n' "$build_type" ;;
    *)
      error "buildType invalido: '$build_type'. Use debug ou release."
      exit 1
      ;;
  esac
}

build_task_name() {
  local build_type="$1"
  printf ':app:assemble%s\n' "${build_type^}"
}

apk_paths_from_variant() {
  local build_type="$1"
  local dir="$PROJECT_ROOT/app/build/outputs/apk/$build_type"
  local -a apks=()

  if [[ ! -d "$dir" ]]; then
    return 1
  fi

  mapfile -t apks < <(find "$dir" -maxdepth 1 -type f -name '*.apk' ! -name '*-unsigned.apk' | sort)
  if [[ ${#apks[@]} -eq 0 ]]; then
    mapfile -t apks < <(find "$dir" -maxdepth 1 -type f -name '*.apk' | sort)
  fi

  if [[ ${#apks[@]} -eq 0 ]]; then
    return 1
  fi

  printf '%s\n' "${apks[@]}"
}

apk_path_from_variant() {
  local build_type="$1"
  local apk

  apk="$(apk_paths_from_variant "$build_type" | head -n 1 || true)"
  if [[ -n "$apk" ]]; then
    printf '%s\n' "$apk"
    return 0
  fi

  return 1
}

device_abis() {
  local abilist=""
  local abi=""

  abilist="$(adb_cmd shell getprop ro.product.cpu.abilist 2>/dev/null | tr -d '\r' || true)"
  if [[ -n "$abilist" ]]; then
    printf '%s\n' "$abilist" | tr ',' '\n' | sed '/^$/d'
    return 0
  fi

  abi="$(adb_cmd shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r' || true)"
  if [[ -n "$abi" ]]; then
    printf '%s\n' "$abi"
    return 0
  fi

  return 1
}

apk_name_matches_abi() {
  local apk_name="$1"
  local abi="$2"

  [[ "$apk_name" == *-"$abi"-* ]]
}

apk_name_has_known_abi() {
  local apk_name="$1"

  case "$apk_name" in
    *-arm64-v8a-*|*-armeabi-v7a-*|*-armeabi-*|*-x86_64-*|*-x86-*)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

select_apk_for_device() {
  local build_type="$1"
  local -a apks=()
  local -a abis=()
  local abi
  local apk
  local apk_name

  mapfile -t apks < <(apk_paths_from_variant "$build_type" || true)
  if [[ ${#apks[@]} -eq 0 ]]; then
    return 1
  fi

  mapfile -t abis < <(device_abis || true)

  for abi in "${abis[@]}"; do
    for apk in "${apks[@]}"; do
      apk_name="$(basename "$apk")"
      if apk_name_matches_abi "$apk_name" "$abi"; then
        printf '%s\n' "$apk"
        return 0
      fi
    done
  done

  for apk in "${apks[@]}"; do
    apk_name="$(basename "$apk")"
    if [[ "$apk_name" == "app-$build_type.apk" ]] || [[ "$apk_name" == *universal*.apk ]]; then
      printf '%s\n' "$apk"
      return 0
    fi
  done

  for apk in "${apks[@]}"; do
    apk_name="$(basename "$apk")"
    if ! apk_name_has_known_abi "$apk_name"; then
      printf '%s\n' "$apk"
      return 0
    fi
  done

  return 1
}

run_gradle() {
  require_gradle
  ensure_android_sdk_ready
  "$GRADLEW" "$@" --no-daemon
}

build_variant() {
  local build_type="$1"
  local task
  task="$(build_task_name "$build_type")"
  log "Executando $task"
  run_gradle "$task"
}

install_variant() {
  local build_type="$1"
  local apk
  local install_output=""
  local -a available_apks=()
  local -a target_abis=()

  ensure_device_ready
  build_variant "$build_type"

  mapfile -t available_apks < <(apk_paths_from_variant "$build_type" || true)
  if [[ ${#available_apks[@]} -eq 0 ]]; then
    error "Nao encontrei APK da variante '$build_type'."
    exit 1
  fi

  apk="$(select_apk_for_device "$build_type" || true)"
  if [[ -z "$apk" ]]; then
    mapfile -t target_abis < <(device_abis || true)
    if [[ ${#target_abis[@]} -gt 0 ]]; then
      error "Nenhum APK compativel com as ABIs do device (${target_abis[*]})."
    else
      error "Nao foi possivel detectar ABI do device e nao ha APK universal disponivel."
    fi
    printf '[cirabit] APKs disponiveis para %s:\n' "$build_type" >&2
    for apk in "${available_apks[@]}"; do
      printf '[cirabit]  - %s\n' "$apk" >&2
    done
    exit 1
  fi

  log "Instalando $apk"
  if install_output="$(adb_cmd install -r "$apk" 2>&1)"; then
    printf '%s\n' "$install_output"
    return 0
  fi

  printf '%s\n' "$install_output" >&2
  if [[ "$install_output" == *"INSTALL_FAILED_UPDATE_INCOMPATIBLE"* ]]; then
    log "Assinatura incompativel detectada para $APP_ID; desinstalando e tentando novamente."
    adb_cmd uninstall "$APP_ID" || true
    adb_cmd install -r "$apk"
    return 0
  fi

  return 1
}

run_app() {
  local build_type="$1"
  install_variant "$build_type"
  log "Abrindo $MAIN_ACTIVITY"
  adb_cmd shell am start -n "$MAIN_ACTIVITY"
}

logs() {
  ensure_device_ready
  local pid
  pid="$(adb_cmd shell pidof -s "$APP_ID" 2>/dev/null | tr -d '\r' || true)"
  if [[ -n "$pid" ]]; then
    log "Mostrando logs do PID $pid (Ctrl+C para sair)"
    adb_cmd logcat --pid="$pid"
  else
    log "App nao esta aberto; mostrando logcat geral (Ctrl+C para sair)"
    adb_cmd logcat
  fi
}

COMMAND="help"

while [[ $# -gt 0 ]]; do
  case "$1" in
    -d|--device)
      if [[ $# -lt 2 ]]; then
        error "Faltou serial depois de $1"
        exit 1
      fi
      DEVICE_SERIAL="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      COMMAND="$1"
      shift
      break
      ;;
  esac
done

BUILD_TYPE="$(normalize_build_type "${1:-debug}")"

cd "$PROJECT_ROOT"

case "$COMMAND" in
  build)
    build_variant "$BUILD_TYPE"
    if apk="$(apk_path_from_variant "$BUILD_TYPE" || true)"; then
      log "APK: $apk"
    fi
    ;;
  bundle)
    log "Executando :app:bundleRelease"
    run_gradle ":app:bundleRelease"
    log "AAB: $PROJECT_ROOT/app/build/outputs/bundle/release/app-release.aab"
    ;;
  test)
    log "Executando :app:testDebugUnitTest"
    run_gradle ":app:testDebugUnitTest"
    ;;
  test-android)
    ensure_device_ready
    log "Executando :app:connectedDebugAndroidTest"
    run_gradle ":app:connectedDebugAndroidTest"
    ;;
  lint)
    log "Executando :app:lint"
    run_gradle ":app:lint"
    ;;
  doctor)
    doctor
    ;;
  install)
    install_variant "$BUILD_TYPE"
    ;;
  run)
    run_app "$BUILD_TYPE"
    ;;
  uninstall)
    ensure_device_ready
    log "Desinstalando $APP_ID"
    adb_cmd uninstall "$APP_ID" || true
    ;;
  clean)
    log "Executando clean"
    run_gradle "clean"
    ;;
  devices)
    require_adb
    adb devices -l
    ;;
  logs)
    logs
    ;;
  apk-path)
    if apk="$(apk_path_from_variant "$BUILD_TYPE" || true)"; then
      printf '%s\n' "$apk"
    else
      error "APK da variante '$BUILD_TYPE' ainda nao existe. Rode: ./script.sh build $BUILD_TYPE"
      exit 1
    fi
    ;;
  all)
    run_gradle "clean" ":app:testDebugUnitTest"
    install_variant "debug"
    ;;
  help|"")
    usage
    ;;
  *)
    error "Comando desconhecido: $COMMAND"
    usage
    exit 1
    ;;
esac
