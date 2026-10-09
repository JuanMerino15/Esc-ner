import React, { useEffect, useMemo, useRef, useState } from "react";
import { uploadFile } from "./api/api";
import styles from "./Scanner.module.css";

/* ───────── Constantes ───────── */
const MAX_SIZE = 10 * 1024 * 1024;

// Etapas alineadas con lo que hace el backend
const STAGES = ["Captura", "OCR", "Extracción", "Validación", "Resultado"];
const STAGE_FROM = [0, 12, 40, 70];
const STAGE_HINT = [
  "Normalizando la imagen y corrigiendo la inclinación…",
  "Leyendo el texto con varias pasadas de OCR…",
  "Votando nombre y clave entre las pasadas…",
  "Validando el formato de la clave y el QR…",
];

const ESTADOS = [
  "", "Aguascalientes", "Baja California", "Baja California Sur", "Campeche", "Coahuila", "Colima",
  "Chiapas", "Chihuahua", "Ciudad de México", "Durango", "Guanajuato", "Guerrero", "Hidalgo", "Jalisco",
  "Estado de México", "Michoacán", "Morelos", "Nayarit", "Nuevo León", "Oaxaca", "Puebla", "Querétaro",
  "Quintana Roo", "San Luis Potosí", "Sinaloa", "Sonora", "Tabasco", "Tamaulipas", "Tlaxcala", "Veracruz",
  "Yucatán", "Zacatecas", "Nacido en el extranjero",
];
const SEXOS = ["", "Hombre", "Mujer"];

const PARTICLES = new Set(["DE", "DEL", "LA", "LAS", "LOS", "MC", "VAN", "VON"]);
const SEGMENTS = [
  { cap: "Iniciales", size: 6 },
  { cap: "Nacimiento", size: 6 },
  { cap: "Entidad", size: 2 },
  { cap: "Sexo", size: 1 },
  { cap: "Homoclave", size: 3 },
];

/* ───────── Utilidades (todo se deriva en el front; el backend no cambia) ─────────
   NOTA: splitNombre() y parseClave() NO se modificaron. Lo único que cambia es que
   ahora pueden recibir texto corregido a mano por la persona (ver "valores efectivos"
   dentro del componente), en vez de solo el texto crudo que entregó el OCR. */

// El INE imprime: apellido paterno, apellido materno, nombre(s)
function splitNombre(full) {
  const t = (full || "").trim().split(/\s+/).filter(Boolean);
  if (t.length < 2) return { paterno: "", materno: "", nombres: t.join(" ") };
  const take = () => {
    const g = [];
    while (t.length > 1 && PARTICLES.has(t[0])) g.push(t.shift());
    if (t.length) g.push(t.shift());
    return g.join(" ");
  };
  const paterno = take();
  const materno = t.length > 1 ? take() : "";
  return { paterno, materno, nombres: t.join(" ") };
}

// Clave: 6 letras + AAMMDD + entidad(2) + H/M + homoclave(3)
function parseClave(c) {
  const m = /^([A-Z]{6})(\d{2})(\d{2})(\d{2})(\d{2})([HM])(\d{3})$/.exec(c || "");
  if (!m) return null;
  const [, letras, yy, mm, dd, st, sx, homo] = m;
  const now = new Date();
  let year = 2000 + Number(yy);
  if (year > now.getFullYear() - 17) year -= 100; // nadie con INE tiene menos de 17 años
  let edad = now.getFullYear() - year;
  const cumplio =
    now.getMonth() + 1 > Number(mm) || (now.getMonth() + 1 === Number(mm) && now.getDate() >= Number(dd));
  if (!cumplio) edad -= 1;
  return {
    partes: [letras, `${yy}${mm}${dd}`, st, sx, homo],
    fecha: `${dd}/${mm}/${year}`,
    edad: `${edad} años`,
    edadNum: edad,
    sexo: sx === "H" ? "Hombre" : "Mujer",
    entidad: ESTADOS[Number(st)] || "",
  };
}

/* ───────── Subcomponentes ───────── */
// Ahora editable: admite texto libre o un <select> (type="select"), muestra un
// indicador "· editado" y un botón ↺ para restaurar el valor detectado por el OCR.
function Field({ id, label, value, onChange, onReset, isEdited, wide, warn, type = "text", options }) {
  const wrapClass = `${styles.field} ${value ? styles.filled : ""} ${warn ? styles.warnField : ""} ${wide ? styles.wide : ""}`;
  return (
    <div className={wrapClass}>
      <label className={styles.label} htmlFor={id}>
        {label}
        {isEdited && (
          <span style={{ marginLeft: 6, fontSize: "0.72em", fontWeight: 600, color: "#5b2a86" }}>
            · editado
          </span>
        )}
      </label>
      <div style={{ display: "flex", alignItems: "center", gap: 6 }}>
        {type === "select" ? (
          <select
            id={id}
            value={value || ""}
            onChange={(e) => onChange(e.target.value)}
            className={styles.input}
            style={{ flex: 1 }}
          >
            {options.map((opt) => (
              <option key={opt || "_empty"} value={opt}>
                {opt || "Selecciona…"}
              </option>
            ))}
          </select>
        ) : (
          <input
            id={id}
            value={value || ""}
            onChange={(e) => onChange(e.target.value)}
            placeholder="Sin datos aún"
            className={styles.input}
            style={{ flex: 1 }}
          />
        )}
        {isEdited && onReset && (
          <button
            type="button"
            onClick={onReset}
            title="Restaurar valor detectado por OCR"
            aria-label={`Restaurar ${label} al valor detectado`}
            style={{
              flexShrink: 0,
              border: "1px solid #d3d9ec",
              background: "#fff",
              borderRadius: 6,
              width: 26,
              height: 26,
              cursor: "pointer",
              fontSize: 14,
              lineHeight: 1,
              color: "#5b2a86",
            }}
          >
            ↺
          </button>
        )}
      </div>
    </div>
  );
}

/* ───────── Pantalla ───────── */
export default function App() {
  const fileRef = useRef(null);
  const timerRef = useRef(null);
  const startRef = useRef(0);

  const [preview, setPreview] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [nombre, setNombre] = useState("");
  const [clave, setClave] = useState("");
  const [meta, setMeta] = useState(null);
  const [progress, setProgress] = useState(0);
  const [elapsed, setElapsed] = useState(0);
  const [done, setDone] = useState(false);
  const [dragging, setDragging] = useState(false);

  // ── Correcciones manuales ──────────────────────────────────────────────
  // `touched` guarda qué campos editó la persona a mano. `values` guarda el
  // texto que escribió en esos campos. Un campo NO tocado sigue mostrando el
  // valor calculado por splitNombre()/parseClave() como siempre; en cuanto se
  // edita, ese campo pasa a usar su propio valor hasta que se restaure (↺).
  const [touched, setTouched] = useState(new Set());
  const [values, setValues] = useState({});

  const setValue = (key) => (val) => {
    setTouched((prev) => new Set(prev).add(key));
    setValues((prev) => ({ ...prev, [key]: val }));
  };
  const resetValue = (key) => () => {
    setTouched((prev) => {
      const next = new Set(prev);
      next.delete(key);
      return next;
    });
    setValues((prev) => {
      const next = { ...prev };
      delete next[key];
      return next;
    });
  };
  const restaurarTodo = () => {
    setTouched(new Set());
    setValues({});
  };

  useEffect(() => {
    return () => {
      if (preview) URL.revokeObjectURL(preview);
    };
  }, [preview]);

  useEffect(() => () => stopTimer(), []);

  const stopTimer = () => {
    if (timerRef.current) clearInterval(timerRef.current);
    timerRef.current = null;
  };

  // El backend responde una sola vez (pasadas A/B/C hasta 60 s), así que el avance es una
  // curva por tiempo: sube rápido al inicio y se acerca a 92% sin llegar hasta que hay respuesta.
  const startTimer = () => {
    stopTimer();
    startRef.current = Date.now();
    timerRef.current = setInterval(() => {
      const ms = Date.now() - startRef.current;
      setElapsed(ms);
      setProgress(92 * (1 - Math.exp(-ms / 7000)));
    }, 100);
  };

  const handleFile = async (file) => {
    if (!file) return;
    if (!file.type.startsWith("image/")) {
      setError("Selecciona una imagen en formato JPG o PNG.");
      return;
    }
    if (file.size > MAX_SIZE) {
      setError("La imagen supera los 10 MB. Elige una más ligera.");
      return;
    }
    setError(null);
    setDone(false);
    setMeta(null);
    setNombre("");
    setClave("");
    setProgress(0);
    setElapsed(0);
    // Un escaneo nuevo reemplaza por completo cualquier corrección manual anterior.
    setTouched(new Set());
    setValues({});
    setPreview(URL.createObjectURL(file));
    setLoading(true);
    startTimer();
    try {
      const data = await uploadFile(file);
      const fields = data?.fields ?? {};
      setNombre(fields.nombre || "");
      setClave(fields.claveElector || "");
      setMeta({
        confidence: Number(data?.ocrConfidence ?? 0),
        needsReview: !!data?.needsReview,
        claveValida: !!data?.claveValida,
        esReverso: !!data?.esReverso,
        etapa: data?.debug?.etapa ?? "—",
        pasadas: data?.debug?.pasadas ?? 0,
        ms: data?.debug?.ms ?? 0,
        rawText: data?.rawText ?? "",
      });
      setProgress(100);
      setDone(true);
    } catch (err) {
      setError(err?.message || "Error al procesar");
      setProgress(0);
    } finally {
      setElapsed(Date.now() - startRef.current);
      stopTimer();
      setLoading(false);
    }
  };

  const onFileChange = (e) => {
    const file = e.target.files?.[0];
    if (!file) return;
    handleFile(file);
    e.target.value = ""; // permite volver a elegir la misma imagen
  };

  const onDrop = (e) => {
    e.preventDefault();
    setDragging(false);
    const file = e.dataTransfer?.files?.[0];
    if (file) handleFile(file);
  };

  const onDragOver = (e) => {
    e.preventDefault();
    if (!loading) setDragging(true);
  };

  const reset = () => {
    stopTimer();
    setPreview(null);
    setNombre("");
    setClave("");
    setMeta(null);
    setError(null);
    setProgress(0);
    setElapsed(0);
    setDone(false);
    setTouched(new Set());
    setValues({});
    if (fileRef.current) fileRef.current.value = "";
  };

  const openPicker = () => !loading && fileRef.current?.click();
  const onDropzoneKey = (e) => {
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      openPicker();
    }
  };

  /* ───────── Valores efectivos (OCR + correcciones manuales) ─────────
     Mismo splitNombre()/parseClave() de siempre; solo cambia su insumo:
     el texto corregido a mano si existe, si no, el texto crudo del OCR. */
  const effectiveNombre = touched.has("nombreCompleto") ? values.nombreCompleto ?? "" : nombre;
  const partesCalc = useMemo(() => splitNombre(effectiveNombre), [effectiveNombre]);
  const effectivePaterno = touched.has("paterno") ? values.paterno ?? "" : partesCalc.paterno;
  const effectiveMaterno = touched.has("materno") ? values.materno ?? "" : partesCalc.materno;
  const effectiveNombres = touched.has("nombres") ? values.nombres ?? "" : partesCalc.nombres;

  const effectiveClave = touched.has("clave") ? values.clave ?? "" : clave;
  const infoCalc = useMemo(() => parseClave(effectiveClave), [effectiveClave]);
  const effectiveFecha = touched.has("fecha") ? values.fecha ?? "" : infoCalc?.fecha ?? "";
  const effectiveEdad = touched.has("edad") ? values.edad ?? "" : infoCalc?.edad ?? "";
  const effectiveSexo = touched.has("sexo") ? values.sexo ?? "" : infoCalc?.sexo ?? "";
  const effectiveEntidad = touched.has("entidad") ? values.entidad ?? "" : infoCalc?.entidad ?? "";

  let activeStage = -1;
  if (preview) {
    activeStage = done ? STAGES.length : STAGE_FROM.filter((v) => progress >= v).length - 1;
  }
  const stageClass = (i) => {
    if (i < activeStage) return styles.complete;
    if (i === activeStage && loading) return styles.active;
    return "";
  };

  const pct = Math.round(progress);
  const seconds = (elapsed / 1000).toFixed(1);
  const status = loading ? "Procesando…" : done ? "Lectura completa" : "Listo para escanear";

  let caption = "Sube o arrastra una foto de tu INE para comenzar.";
  if (loading) {
    caption = elapsed > 15000
      ? "La foto es difícil de leer: el servidor hace pasadas de refuerzo (puede tardar hasta 60 s)."
      : STAGE_HINT[Math.max(0, activeStage)];
  } else if (done && meta) {
    caption = `Etapa ${meta.etapa} · ${meta.pasadas} pasadas de OCR · ${(meta.ms / 1000).toFixed(1)} s`;
  }

  // Una clave puede tener formato válido y aun así una fecha absurda si el OCR confundió un dígito.
  // Si la persona ya corrigió la fecha o la edad a mano, dejamos de marcarla como dudosa.
  const dudosa =
    !touched.has("fecha") && !touched.has("edad") && !!infoCalc && infoCalc.edadNum > 95;
  const iniciales = `${effectiveNombres[0] || ""}${effectivePaterno[0] || ""}`;

  let claveChip = ["Pendiente", styles.chipIdle];
  if (effectiveClave) {
    if (dudosa) claveChip = ["Revisar fecha", styles.chipWarn];
    else {
      const claveOk = touched.has("clave") ? !!infoCalc : meta?.claveValida ?? !!infoCalc;
      claveChip = claveOk ? ["Clave válida", styles.chipOk] : ["Revisar clave", styles.chipWarn];
    }
  } else if (meta?.esReverso) claveChip = ["Solo se lee del frente", styles.chipIdle];

  let reviewMsg = null;
  if (meta?.needsReview) reviewMsg = "Revisa los datos: la lectura tuvo poco respaldo entre pasadas. Compáralos con la credencial.";
  else if (dudosa) reviewMsg = `La fecha deducida de la clave (${infoCalc.fecha}) resulta poco probable. Compárala con tu credencial: el OCR pudo confundir algún dígito.`;

  const conf = meta?.confidence ?? 0;
  const confColor = conf >= 80 ? "#0e9f8e" : conf >= 55 ? "#d98e04" : "#d1226b";

  return (
    <div className={styles.page}>
      <div className={styles.container}>
        <header className={styles.header}>
          <div className={styles.seal} aria-hidden>
            <svg width="26" height="26" viewBox="0 0 24 24" fill="none">
              <path d="M12 3l7 3v5c0 4.5-3 8.2-7 10-4-1.8-7-5.5-7-10V6l7-3z" stroke="currentColor" strokeWidth="1.6" strokeLinejoin="round" />
              <path d="M8.8 12.2l2.2 2.2 4.2-4.4" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </div>
          <div>
            <h1 className={styles.title}>Escáner de INE</h1>
            <p className={styles.subtitle}>Captura rápida y segura de datos</p>
          </div>
          <span
            className={`${styles.badge} ${loading ? styles.badgeBusy : ""} ${done ? styles.badgeDone : ""}`}
            aria-live="polite"
          >
            {status}
          </span>
        </header>

        <main className={styles.layout}>
          {/* ───────── Captura ───────── */}
          <section className={styles.capture}>
            <h2 className={styles.cardTitle}>Captura del documento</h2>

            {error && <div className={styles.errorBanner} role="alert">{error}</div>}

            <input ref={fileRef} type="file" accept="image/*" style={{ display: "none" }} onChange={onFileChange} />

            <div
              className={`${styles.dropzone} ${dragging ? styles.dragging : ""} ${loading ? styles.disabled : ""}`}
              role="button"
              tabIndex={0}
              aria-label="Seleccionar imagen de la INE"
              onClick={openPicker}
              onKeyDown={onDropzoneKey}
              onDrop={onDrop}
              onDragOver={onDragOver}
              onDragLeave={() => setDragging(false)}
            >
              <span className={`${styles.corner} ${styles.tl}`} />
              <span className={`${styles.corner} ${styles.tr}`} />
              <span className={`${styles.corner} ${styles.bl}`} />
              <span className={`${styles.corner} ${styles.br}`} />

              {preview ? (
                <div className={styles.preview}>
                  <img src={preview} alt="Vista previa INE" className={styles.previewImg} />
                </div>
              ) : (
                <div className={styles.dropInner}>
                  <svg className={styles.icon} width="52" height="52" viewBox="0 0 24 24" fill="none" aria-hidden>
                    <rect x="2" y="5" width="20" height="14" rx="2" stroke="currentColor" strokeWidth="1.5" fill="none" />
                    <circle cx="8.5" cy="11.5" r="2" stroke="currentColor" strokeWidth="1.5" fill="none" />
                    <path d="M12 8h6v2h-6zM12 12h6v2h-6z" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" fill="none" />
                  </svg>
                  <p className={styles.dropText}>Arrastra tu INE aquí o haz clic para seleccionar</p>
                  <p className={styles.hint}>Formatos soportados: JPG, PNG. Tamaño máximo: 10MB</p>
                </div>
              )}
              {loading && <div className={styles.beam} aria-hidden />}
            </div>

            {/* ───────── Barra de carga ───────── */}
            <div className={styles.meter}>
              <div className={styles.meterHead}>
                <span className={styles.meterLabel}>
                  {loading ? "Leyendo documento" : done ? "Documento leído" : "En espera"}
                </span>
                <span className={styles.meterPct}>
                  {pct}%{loading && <small className={styles.meterTime}> · {seconds} s</small>}
                </span>
              </div>

              {/* Estilos críticos en línea: la barra se ve aunque haya CSS global (index.css) que interfiera */}
              <div
                className={styles.track}
                role="progressbar"
                aria-valuemin={0}
                aria-valuemax={100}
                aria-valuenow={pct}
                aria-label="Progreso de lectura"
                style={{
                  display: "block",
                  position: "relative",
                  width: "100%",
                  height: 18,
                  boxSizing: "border-box",
                  borderRadius: 999,
                  overflow: "hidden",
                  background: "#d3d9ec",
                  boxShadow: "inset 0 1px 3px rgba(17,27,58,.25)",
                }}
              >
                <div
                  className={styles.fill}
                  style={{
                    display: "block",
                    height: "100%",
                    width: `${progress}%`,
                    minWidth: loading ? 14 : 0,
                    borderRadius: 999,
                    background: done
                      ? "#0e9f8e"
                      : "linear-gradient(90deg, #111b3a 0%, #5b2a86 55%, #d1226b 100%)",
                  }}
                >
                  {loading && <span className={styles.shine} aria-hidden />}
                </div>
              </div>

              <ol className={styles.steps}>
                {STAGES.map((s, i) => (
                  <li key={s} className={`${styles.step} ${stageClass(i)}`}>{s}</li>
                ))}
              </ol>
              <p className={styles.caption} aria-live="polite">{caption}</p>
            </div>

            <div className={styles.actions}>
              <button className={styles.primary} onClick={openPicker} disabled={loading}>
                {loading ? "Procesando…" : "Subir INE"}
              </button>
              <button className={styles.ghost} onClick={reset} disabled={loading}>Limpiar</button>
            </div>
          </section>

          {/* ───────── Datos detectados ───────── */}
          <div className={styles.data}>
            {done && meta && (
              <div className={`${styles.notice} ${reviewMsg ? styles.noticeWarn : styles.noticeOk}`} role="status">
                {reviewMsg || "Lectura consistente: los datos coinciden entre varias pasadas."}
              </div>
            )}

            {touched.size > 0 && (
              <div
                style={{
                  display: "flex",
                  alignItems: "center",
                  justifyContent: "space-between",
                  gap: 10,
                  background: "#eef0fa",
                  color: "#111b3a",
                  borderRadius: 10,
                  padding: "10px 14px",
                  fontSize: "0.9rem",
                }}
              >
                <span>
                  Has corregido {touched.size} campo{touched.size === 1 ? "" : "s"} manualmente.
                </span>
                <button
                  type="button"
                  onClick={restaurarTodo}
                  style={{
                    border: "1px solid #c7cdea",
                    background: "#fff",
                    borderRadius: 8,
                    padding: "4px 10px",
                    fontSize: "0.85rem",
                    cursor: "pointer",
                    color: "#5b2a86",
                    fontWeight: 600,
                  }}
                >
                  Restaurar todo
                </button>
              </div>
            )}

            <section className={styles.panel}>
              <div className={styles.panelHead}>
                <h2 className={styles.panelTitle}>Titular</h2>
                <span className={`${styles.chip} ${effectiveNombre ? styles.chipOk : styles.chipIdle}`}>
                  {effectiveNombre ? "Detectado" : "Pendiente"}
                </span>
              </div>
              <div className={styles.titular}>
                <div className={`${styles.monogram} ${effectiveNombre ? styles.monogramOn : ""}`} aria-hidden>
                  {effectiveNombre ? iniciales : "?"}
                </div>
                <div className={styles.fields3}>
                  <Field
                    id="ine-nombre"
                    label="Nombre completo"
                    value={effectiveNombre}
                    onChange={setValue("nombreCompleto")}
                    onReset={resetValue("nombreCompleto")}
                    isEdited={touched.has("nombreCompleto")}
                    wide
                  />
                  <Field
                    id="ine-paterno"
                    label="Apellido paterno"
                    value={effectivePaterno}
                    onChange={setValue("paterno")}
                    onReset={resetValue("paterno")}
                    isEdited={touched.has("paterno")}
                  />
                  <Field
                    id="ine-materno"
                    label="Apellido materno"
                    value={effectiveMaterno}
                    onChange={setValue("materno")}
                    onReset={resetValue("materno")}
                    isEdited={touched.has("materno")}
                  />
                  <Field
                    id="ine-nombres"
                    label="Nombre(s)"
                    value={effectiveNombres}
                    onChange={setValue("nombres")}
                    onReset={resetValue("nombres")}
                    isEdited={touched.has("nombres")}
                  />
                </div>
              </div>
              {effectiveNombre && <p className={styles.note}>Apellidos y nombre(s) se separan a partir del nombre completo. Puedes corregir cualquier campo si el OCR se equivocó.</p>}
            </section>

            <section className={`${styles.panel} ${styles.panelDark}`}>
              <div className={styles.panelHead}>
                <h2 className={styles.panelTitle}>Clave de elector</h2>
                <span className={`${styles.chip} ${claveChip[1]}`}>{claveChip[0]}</span>
              </div>
              <div className={styles.split}>
                <div>
                  <div className={styles.segs} aria-hidden>
                    {SEGMENTS.map((sg, i) => (
                      <div key={sg.cap} className={styles.seg} style={{ flexGrow: sg.size }}>
                        <span className={styles.segVal}>{infoCalc ? infoCalc.partes[i] : "·".repeat(sg.size)}</span>
                        <span className={styles.segCap}>{sg.cap}</span>
                      </div>
                    ))}
                  </div>
                  <Field
                    id="ine-clave"
                    label="Clave de elector"
                    value={effectiveClave}
                    onChange={setValue("clave")}
                    onReset={resetValue("clave")}
                    isEdited={touched.has("clave")}
                  />
                </div>
                <div className={styles.fields}>
                  <Field
                    id="ine-nac"
                    label="Fecha de nacimiento"
                    value={effectiveFecha}
                    warn={dudosa}
                    onChange={setValue("fecha")}
                    onReset={resetValue("fecha")}
                    isEdited={touched.has("fecha")}
                  />
                  <Field
                    id="ine-edad"
                    label="Edad"
                    value={effectiveEdad}
                    warn={dudosa}
                    onChange={setValue("edad")}
                    onReset={resetValue("edad")}
                    isEdited={touched.has("edad")}
                  />
                  <Field
                    id="ine-sexo"
                    label="Sexo"
                    value={effectiveSexo}
                    type="select"
                    options={SEXOS}
                    onChange={setValue("sexo")}
                    onReset={resetValue("sexo")}
                    isEdited={touched.has("sexo")}
                  />
                  <Field
                    id="ine-entidad"
                    label="Entidad de nacimiento"
                    value={effectiveEntidad}
                    type="select"
                    options={ESTADOS}
                    onChange={setValue("entidad")}
                    onReset={resetValue("entidad")}
                    isEdited={touched.has("entidad")}
                  />
                </div>
              </div>
              {(infoCalc || effectiveFecha || effectiveSexo || effectiveEntidad) && (
                <p className={styles.note}>Fecha, sexo y entidad se calculan a partir de la clave. Puedes corregir cualquiera si el OCR falló.</p>
              )}
            </section>

            <section className={styles.panel}>
              <div className={styles.panelHead}>
                <h2 className={styles.panelTitle}>Calidad de lectura</h2>
              </div>
              <div className={styles.qual}>
                <div className={styles.gauge}>
                  <svg width="112" height="112" viewBox="0 0 80 80" role="img" aria-label={`Confianza del OCR ${meta ? conf : 0}%`}>
                    <circle cx="40" cy="40" r="34" fill="none" stroke="#dfe4f2" strokeWidth="8" />
                    <circle
                      cx="40" cy="40" r="34" fill="none" stroke={confColor} strokeWidth="8" strokeLinecap="round"
                      strokeDasharray={`${(meta ? conf : 0) * 2.1363} 213.63`}
                      transform="rotate(-90 40 40)"
                      style={{ transition: "stroke-dasharray .8s ease-out" }}
                    />
                    <text x="40" y="46" textAnchor="middle" fontSize="17" fontWeight="700" fill="#111b3a">
                      {meta ? `${conf}%` : "—"}
                    </text>
                  </svg>
                  <span>Confianza del OCR</span>
                </div>
                <div className={styles.stats}>
                  <div className={styles.stat}><strong>{meta ? (meta.esReverso ? "Reverso" : "Frente") : "—"}</strong><span>Lado detectado</span></div>
                  <div className={styles.stat}><strong>{meta ? meta.etapa : "—"}</strong><span>Etapa de lectura</span></div>
                  <div className={styles.stat}><strong>{meta ? meta.pasadas : "—"}</strong><span>Pasadas de OCR</span></div>
                  <div className={styles.stat}><strong>{meta ? `${(meta.ms / 1000).toFixed(1)} s` : "—"}</strong><span>Tiempo total</span></div>
                </div>
              </div>
              {meta?.esReverso && (
                <p className={styles.note}>En el reverso solo se lee el nombre (zona MRZ). Para la clave sube el frente.</p>
              )}
              {meta?.rawText && (
                <details className={styles.raw}>
                  <summary>Ver texto leído por el OCR</summary>
                  <pre>{meta.rawText}</pre>
                </details>
              )}
            </section>
          </div>
        </main>
      </div>
    </div>
  );
}
