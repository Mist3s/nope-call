#!/usr/bin/env bash
# Собирает активы релиза: переименовывает APK, считает суммы, готовит latest.json (ТЗ §15.4).
#
# Апдейтер в приложении сверяет sha256 скачанного файла с этим latest.json, а отпечаток
# сертификата подписи — с отпечатком уже установленной копии. Поэтому оба значения берутся
# из фактически собранных файлов, а не проставляются руками.
#
#   Запуск: scripts/collect-release-assets.sh 1.2.3
set -euo pipefail

VERSION="${1:?укажи версию, например 1.2.3}"
REPO="${GITHUB_REPOSITORY:-Mist3s/nope-call}"
APK_DIR="build/app/outputs/flutter-apk"
DIST="dist"

rm -rf "$DIST"
mkdir -p "$DIST"

# Отпечаток сертификата подписи. Публикуется в release notes, чтобы непрерывность подписи
# можно было проверить снаружи: потеря ключа означает невозможность обновить установленные копии.
APKSIGNER="$(find "${ANDROID_HOME:-$ANDROID_SDK_ROOT}/build-tools" -name apksigner | sort -V | tail -1)"
UNIVERSAL_SRC="$APK_DIR/app-release.apk"

# Извлекается ровно 64 шестнадцатеричных знака, а не «поле после первого двоеточия».
#
# Так было: `awk -F': ' '{print $2}'`. Метка строки у apksigner на разных версиях
# build-tools содержит своё двоеточие, и на раннере вторым полем оказалась сама подпись
# строки — в манифест ушло `CERTIFICATE SHA-256 DIGEST`. Апдейтер отказался обновляться
# (и правильно), но узналось это на телефоне пользователя, а не в сборке.
CERT_SHA="$(
    "$APKSIGNER" verify --print-certs "$UNIVERSAL_SRC" 2>/dev/null |
        sed -nE 's/.*certificate SHA-256 digest:[[:space:]]*([0-9a-fA-F]{64}).*/\1/p' |
        head -1 |
        tr '[:lower:]' '[:upper:]'
)"

# Проверка формата обязательна: непустое значение ещё не значит отпечаток. Манифест
# с мусором в этом поле ломает обновление у всех, кто уже установил приложение,
# и починить его можно только новым релизом.
if ! printf '%s' "$CERT_SHA" | grep -Eq '^[0-9A-F]{64}$'; then
    echo "не удалось прочитать отпечаток сертификата из $UNIVERSAL_SRC" >&2
    echo "получено: \"$CERT_SHA\"" >&2
    echo "вывод apksigner:" >&2
    "$APKSIGNER" verify --print-certs "$UNIVERSAL_SRC" >&2 2>&1 || true
    exit 1
fi
printf '%s\n' "$CERT_SHA" > "$DIST/signing-cert-sha256.txt"

# --split-per-abi даёт по файлу на архитектуру, плюс отдельно собирается универсальный.
# Апдейтер сравнивает versionName как semver, а НЕ versionCode: при сплите Flutter добавляет
# к versionCode смещение по ABI, и числа у файлов одного релиза разные.
declare -a ASSETS=()
add_asset() {
    local src="$1" abi="$2"
    [ -f "$src" ] || return 0
    local dst="nope-call-$VERSION-$abi.apk"
    cp "$src" "$DIST/$dst"
    local sha size
    sha="$(sha256sum "$DIST/$dst" | cut -d' ' -f1)"
    size="$(stat -c%s "$DIST/$dst")"
    ASSETS+=("$(printf '{"abi":"%s","url":"https://github.com/%s/releases/download/v%s/%s","size":%s,"sha256":"%s"}' \
        "$abi" "$REPO" "$VERSION" "$dst" "$size" "$sha")")
    echo "  $dst  $((size / 1024 / 1024)) МБ"
}

echo "Активы релиза $VERSION:"
add_asset "$APK_DIR/app-arm64-v8a-release.apk" "arm64-v8a"
add_asset "$APK_DIR/app-armeabi-v7a-release.apk" "armeabi-v7a"
add_asset "$APK_DIR/app-x86_64-release.apk" "x86_64"
add_asset "$UNIVERSAL_SRC" "universal"

[ "${#ASSETS[@]}" -gt 0 ] || { echo "не найдено ни одного APK" >&2; exit 1; }

( cd "$DIST" && sha256sum ./*.apk | sed 's|\./||' > SHA256SUMS.txt )

BUILD="$(sed -nE 's/^version:[[:space:]]*[^+]+\+([0-9]+).*/\1/p' pubspec.yaml | head -1)"
PRERELEASE=false
case "$VERSION" in *-*) PRERELEASE=true ;; esac

{
    printf '{\n'
    printf '  "version": "%s",\n' "$VERSION"
    printf '  "build": %s,\n' "$BUILD"
    printf '  "prerelease": %s,\n' "$PRERELEASE"
    printf '  "min_android_sdk": 29,\n'
    printf '  "notes_url": "https://github.com/%s/releases/tag/v%s",\n' "$REPO" "$VERSION"
    printf '  "signing_cert_sha256": "%s",\n' "$CERT_SHA"
    printf '  "assets": [\n'
    printf '    %s' "${ASSETS[0]}"
    for a in "${ASSETS[@]:1}"; do printf ',\n    %s' "$a"; done
    printf '\n  ]\n}\n'
} > "$DIST/latest.json"

# Манифест проверяется перед публикацией: релиз с битым отпечатком или битой суммой хуже,
# чем отсутствие релиза, — установленные копии обновиться не смогут.
grep -Eq '"signing_cert_sha256": "[0-9A-F]{64}"' "$DIST/latest.json" || {
    echo "в latest.json отпечаток сертификата не похож на отпечаток" >&2
    exit 1
}
if grep -Ec '"sha256":"[0-9a-f]{64}"' "$DIST/latest.json" | grep -q '^0$'; then
    echo "в latest.json нет ни одной корректной суммы sha256" >&2
    exit 1
fi

echo "Отпечаток подписи: $CERT_SHA"
echo "Готово: $DIST/"
