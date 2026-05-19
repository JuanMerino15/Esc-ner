import React, { useRef, useState } from "react";
import { uploadFile } from "./api/api";
import styles from "./Scanner.module.css";

export default function App() {
  const fileRef = useRef(null);
  const [preview, setPreview] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [nombre, setNombre] = useState("");
  const [clave, setClave] = useState("");

  const handleFile = async (file) => {
    if (!file) return;
    setError(null);
    setPreview(URL.createObjectURL(file));
    setLoading(true);
    try {
      const data = await uploadFile(file);
      const fields = data?.fields ?? {};
      setNombre(fields.nombre || "");
      setClave(fields.claveElector || "");
    } catch (err) {
      setError(err?.message || "Error al procesar");
    } finally {
      setLoading(false);
    }
  };

  const onFileChange = (e) => {
    const file = e.target.files?.[0];
    if (!file) return;
    handleFile(file);
  };

  const onDrop = (e) => {
    e.preventDefault();
    const file = e.dataTransfer?.files?.[0];
    if (file) handleFile(file);
  };

  const onDragOver = (e) => e.preventDefault();

  const reset = () => {
    setPreview(null);
    setNombre("");
    setClave("");
    setError(null);
    if (fileRef.current) fileRef.current.value = "";
  };

  return (
    <div className={styles.page}>
      <div className={styles.container}>
        <header className={styles.header}>
          <h1 className={styles.title}>Escáner de INE</h1>
          <p className={styles.subtitle}>Captura rápida y segura de datos</p>
        </header>

        <main className={styles.card}>

          {error && (
            <div className={styles.errorBanner} role="alert">
              {error}
            </div>
          )}

          <input
            ref={fileRef}
            type="file"
            accept="image/*"
            style={{ display: "none" }}
            onChange={onFileChange}
          />

          <div
            className={`${styles.dropzone} ${loading ? styles.disabled : ""}`}
            onClick={() => !loading && fileRef.current?.click()}
            onDrop={onDrop}
            onDragOver={onDragOver}
          >
            <div className={styles.dropInner}>
              <svg className={styles.icon} width="48" height="48" viewBox="0 0 24 24" fill="none" aria-hidden>
                <rect x="2" y="5" width="20" height="14" rx="2" stroke="currentColor" strokeWidth="1.5" fill="none"/>
                <circle cx="8.5" cy="11.5" r="2" stroke="currentColor" strokeWidth="1.5" fill="none"/>
                <path d="M12 8h6v2h-6zM12 12h6v2h-6z" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" fill="none"/>
              </svg>
              <p className={styles.dropText}>Arrastra tu INE aquí o haz clic para seleccionar</p>
              <p className={styles.hint}>Formatos soportados: JPG, PNG. Tamaño máximo: 10MB</p>
            </div>
          </div>

          {preview && (
            <div className={styles.preview}>
              <img src={preview} alt="Vista previa INE" className={styles.previewImg} />
            </div>
          )}

          <div className={styles.form}>
            <div className={`${styles.field} ${nombre ? styles.filled : ""}`}>
              <label className={styles.label}>Nombre completo</label>
              <input readOnly value={nombre} className={styles.input} />
            </div>
            <div className={`${styles.field} ${clave ? styles.filled : ""}`}>
              <label className={styles.label}>Clave de elector</label>
              <input readOnly value={clave} className={styles.input} />
            </div>
          </div>

          <div className={styles.actions}>
            <button className={styles.primary} onClick={() => fileRef.current?.click()} disabled={loading}>
              {loading ? "Procesando…" : "Subir INE"}
            </button>
            <button className={styles.ghost} onClick={reset} disabled={loading}>
              Limpiar
            </button>
          </div>

          {loading && (
            <div className={styles.loader}>
              <div className={styles.spinner} aria-hidden />
              <div className={styles.loaderText}>Procesando INE...</div>
            </div>
          )}

        </main>
      </div>
    </div>
  );
}