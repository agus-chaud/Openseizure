# HARDWARE RUNBOOK — Testing que SOLO un humano con el Watch 8 puede hacer

> El agente NO puede ejecutar nada de este archivo: requiere el Samsung Galaxy Watch 8 físico
> (y, para el test nocturno, una persona durmiendo). El agente **prepara** este runbook y
> **interpreta** los resultados que vos le pegás; vos sos las manos. Tras cada paso exitoso,
> el humano marca la fase como **Field-Done** en el README.

Plantilla — completar a medida que se ejecutan las fases hardware-gated (2.6, 4.1–4.4).

---

## Pre-requisitos (Fase 0.4)

```bash
# Conectar el watch por ADB Wireless
./scripts/connect_watch.sh 192.168.x.x

# Build + install (NO lo corre el agente; "nunca buildear" es regla del agente)
./scripts/deploy_wear.sh
```

---

## 2.6 — Benchmark de batería (objetivo ≥8h)

- [ ] Cargar el reloj al 100%, iniciar monitoreo, anotar hora.
- [ ] Dejar corriendo sin interacción.
- [ ] Anotar % de batería cada hora.
- [ ] **Pegar al agente**: tabla hora vs %. El agente calcula el drenaje/hora y proyecta autonomía.
- [ ] Criterio Field-Done: ≥8h con monitoreo continuo.

## 4.1 — Test E2E manual (simular convulsión)

- [ ] Con la app activa, sacudir el reloj con movimiento rítmico 1-3Hz por ~30s.
- [ ] Verificar: WARNING → ALARM, vibración en reloj, AlarmActivity + sirena en teléfono, SMS al cuidador.
- [ ] **Pegar al agente**: `adb logcat` del reloj y del teléfono.

## 4.2 — Test nocturno real (8h)

- [ ] Dormir con el reloj puesto y el monitoreo activo.
- [ ] A la mañana: descargar CSV (`adb pull .../files/logs/`) y contar falsas alarmas.
- [ ] **Pegar al agente**: CSV + cantidad de falsas alarmas. El agente analiza jitter, gaps, magnitud.

## 4.3 — Análisis de falsas alarmas + tuning del umbral

- [ ] **Bloqueante**: el umbral está en `CLINICAL_SIGNOFF.md`. NO lo cambia el agente.
- [ ] El agente propone, el humano firma el nuevo valor en CLINICAL_SIGNOFF.md.

## 4.4 — Edge cases

- [ ] Reloj sacado de la muñeca a mitad de la noche → ¿qué hace el sistema?
- [ ] Batería baja → ¿avisa?
- [ ] Bluetooth perdido entre reloj y teléfono → ¿reintenta? ¿avisa?
- [ ] **Pegar al agente**: comportamiento observado en cada caso.

---

## Cómo pegarle resultados al agente

Copiá la salida de logcat / el CSV / la tabla de batería en el chat. El agente la interpreta,
te dice si el criterio se cumple, y SOLO entonces vos marcás Field-Done.
