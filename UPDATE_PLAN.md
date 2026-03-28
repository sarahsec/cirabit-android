# Plano de Atualizacao - Cirabit Android

## Objetivo
Atualizar o app de forma segura e previsivel, reduzindo risco de quebra futura e mantendo foco em seguranca real.

## Principios
- Seguranca primeiro, compatibilidade legada depois.
- Nao manter compatibilidade com cifras antigas/inseguras.
- Toda mudanca relevante deve manter `assembleDebug` verde.
- CI deve bloquear regressao de seguranca e dependencia critica.

## Estado Atual
- [x] JVM e toolchain em Java/Kotlin 17.
- [x] Storage seguro baseado em Tink + Android Keystore.
- [x] Dependabot configurado para `gradle` e `github-actions`.
- [x] CodeQL configurado para `actions` e `java-kotlin`.
- [x] Fluxo biometrico ajustado para compatibilidade entre APIs Android.

## Roadmap por Fase

### Fase 1 - Baseline de Seguranca e Build (concluida)
**Objetivo:** estabilizar base para updates sem regressao.

**Entregas**
- Toolchain JVM 17.
- Ajustes iniciais de biometric/authenticator.
- Build local e CI com verificacao de compilacao.

**Criterio de saida**
- `:app:assembleDebug` e `:app:compileDebugKotlin` passam sem erro.

---

### Fase 2 - Crypto Hardening (concluida)
**Objetivo:** remover dependencia operacional de storage legado.

**Entregas**
- Uso de Tink/Keystore como caminho unico de armazenamento sensivel.
- Remocao de migracao de cifras antigas.
- Remocao de dependencia `androidx.security:security-crypto` (quando nao usada).

**Criterio de saida**
- Nao existir referencia ativa a `EncryptedSharedPreferences`/`MasterKey` no app.

---

### Fase 3 - Deprecations de Alto Risco (concluida)
**Objetivo:** reduzir chance de break em novos Android SDK/AGP.

**Prioridade alta**
1. `MeshForegroundService`: substituir usos de `stopForeground(Boolean)` por API atual. ✅
2. `SystemLocationProvider`: remover `requestSingleUpdate` depreciado. ✅
3. `BluetoothPacketBroadcaster`: revisar chamadas BLE depreciadas. ✅

**Prioridade media**
1. Compose deprecado (`ArrowBack`, `LocalClipboardManager`, `Indicator`). ✅
2. Utilitarios de display depreciados (`defaultDisplay/getMetrics`). ✅
3. Casts inseguros e warnings de unchecked cast. ✅

**Criterio de saida**
- Reducao mensuravel de warnings deprecados em `compileDebugKotlin`. ✅

---

### Fase 4 - Qualidade de Pipeline (proxima)
**Objetivo:** evitar regressao silenciosa em PR.

**Entregas**
- Gate minimo de PR:
  - `./gradlew :app:assembleDebug --no-daemon`
  - `./gradlew lint`
  - `./gradlew test`
- Revisao de regras de dependabot (grupos e limites de PR).
- Ajuste fino de schedule do CodeQL conforme custo/tempo.

**Criterio de saida**
- Todo PR relevante passa em CI antes de merge.

---

### Fase 5 - Preparacao de Release (proxima)
**Objetivo:** reduzir risco operacional no release.

**Entregas**
- Smoke tests manuais em dispositivo real:
  - onboarding
  - app lock (abrir, background, voltar, retry)
  - envio/recebimento em chat
- Validacao de install/update sem perda funcional.
- Changelog de seguranca e compatibilidade por release.

**Criterio de saida**
- Release candidate aprovado com checklist completo.

## Checklist de Execucao por PR
- [ ] Escopo pequeno e reversivel.
- [ ] Build local ok (`assembleDebug`).
- [ ] Sem nova dependencia insegura.
- [ ] Sem reintroduzir API legada de cifra.
- [ ] Notas de risco e rollback descritas na PR.

## Comandos Base
```bash
./gradlew :app:compileDebugKotlin --no-daemon
./gradlew :app:assembleDebug --no-daemon
./gradlew :app:installDebug --no-daemon
./gradlew lint
./gradlew test
```

## Politica de Seguranca para Cifras
- Nao adicionar fallback para cifra legada.
- Nao manter caminho de migracao que perpetue formato inseguro.
- Se dado antigo nao for compativel com o modelo atual seguro, rotacionar identidade/chaves.
