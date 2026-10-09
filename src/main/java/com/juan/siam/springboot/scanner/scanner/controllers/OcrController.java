package com.juan.siam.springboot.scanner.scanner.controllers;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.recognition.software.jdeskew.ImageDeskew;
import net.sourceforge.tess4j.ITessAPI;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.Word;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.File;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Escáner de INE (frente / reverso).
 *
 * Contrato con el front (SIN CAMBIOS):
 *   POST /api/scan  (multipart "file")
 *   200 -> { fields: { nombre, claveElector }, ... }
 *   500 -> { error, message }
 * Campos extra (el front los puede ignorar): needsReview, claveValida, debug (incluye debug.cross).
 *
 * Estrategia:
 *   1) Preprocesa UNA vez (normaliza tamaño, deskew, 3 variantes).
 *   2) Etapa A: 2 pasadas en paralelo. Si coinciden y la clave valida -> responde.
 *   3) Etapa B (solo si hace falta): más pasadas + re-OCR del recorte de la clave (3 variantes).
 *   4) Etapa C (solo si no se leyó nada): prueba rotaciones 90/270/180.
 *   5) Los campos se deciden por VOTACIÓN entre pasadas + validación de formato
 *      + VALIDACIÓN CRUZADA (IneCrossCheck): fecha de nacimiento impresa, CURP, sexo,
 *      entidad, letras del nombre y edad plausible.
 */
@CrossOrigin(origins = "http://localhost:5173")
@RestController
@RequestMapping("/api")
public class OcrController implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(OcrController.class);

    // ------------------------------------------------------------------ config
    private static final String TESSDATA_PATH = "C:/tessdata_best";
    private static final int TARGET_LONG_SIDE = 1600;           // lado largo normalizado (sube O baja)
    private static final int POOL_SIZE =
        Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors()));
    private static final long REQUEST_BUDGET_MS = 60_000L;
    private static final double MIN_SKEW_DEG = 0.7;
    private static final double MAX_SKEW_DEG = 20.0;
    private static final String ALNUM_WL = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 ";
    private static final String MRZ_WL = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789<";

    private static final int PSM_AUTO = ITessAPI.TessPageSegMode.PSM_AUTO;
    private static final int PSM_SPARSE = ITessAPI.TessPageSegMode.PSM_SPARSE_TEXT;
    private static final int PSM_BLOCK = ITessAPI.TessPageSegMode.PSM_SINGLE_BLOCK;

    private final BlockingQueue<Tesseract> pool = new ArrayBlockingQueue<>(POOL_SIZE);
    private final ExecutorService executor;

    public OcrController() {
        this.executor = Executors.newFixedThreadPool(POOL_SIZE, r -> {
            Thread t = new Thread(r, "ocr-worker");
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < POOL_SIZE; i++) pool.offer(createTesseract());
        if (!trainedDataExists()) {
            log.error("No se encontró spa.traineddata en {}", TESSDATA_PATH);
        }
        log.info("OCR listo: pool={} instancias, lado largo objetivo={}px", POOL_SIZE, TARGET_LONG_SIDE);
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }

    private static boolean trainedDataExists() {
        return new File(TESSDATA_PATH, "spa.traineddata").exists();
    }

    private Tesseract createTesseract() {
        Tesseract t = new Tesseract();
        t.setDatapath(TESSDATA_PATH);
        t.setLanguage("spa");
        try {
            t.setOcrEngineMode(ITessAPI.TessOcrEngineMode.OEM_LSTM_ONLY);
            t.setTessVariable("user_defined_dpi", "300");
            t.setTessVariable("preserve_interword_spaces", "1");
            // Sin filtro secundario de ruido de layout (caro y innecesario en documentos).
            t.setTessVariable("textord_heavy_nr", "0");
            // No re-intentar la imagen invertida cuando la confianza es baja:
            // las INE son texto oscuro sobre fondo claro; ese reintento solo cuesta tiempo
            // y es una fuente típica de "letras de más".
            t.setTessVariable("tessedit_do_invert", "0");
        } catch (Exception e) {
            log.warn("No se pudieron fijar variables de Tesseract: {}", e.getMessage());
        }
        return t;
    }

    // =====================================================================
    // POST /api/scan
    // =====================================================================
    @PostMapping("/scan")
    public ResponseEntity<?> scan(@RequestParam("file") MultipartFile file) {

        if (!trainedDataExists()) {
            return ResponseEntity.status(500).body(Map.of(
                "error", "missing_traineddata",
                "message", "No se encontró spa.traineddata en " + TESSDATA_PATH));
        }

        final long t0 = System.currentTimeMillis();
        final long deadline = t0 + REQUEST_BUDGET_MS;

        try {
            BufferedImage original = readImage(file);
            long tRead = System.currentTimeMillis();

            final Prepared prep = Prepared.build(original);
            long tPrep = System.currentTimeMillis();
            log.info("lectura={}ms preproceso={}ms original={}x{} base={}x{}",
                tRead - t0, tPrep - tRead, original.getWidth(), original.getHeight(),
                prep.gray.getWidth(), prep.gray.getHeight());

            List<Pass> passes = new ArrayList<>();

            // ---------------- Etapa A: 2 pasadas en paralelo ----------------
            List<Future<Pass>> stageA = new ArrayList<>();
            stageA.add(submit("STD/AUTO", prep.std, 1.0, null, PSM_AUTO));
            stageA.add(submit("FLAT/AUTO", prep.flat, 1.0, null, PSM_AUTO));

            // El QR (zxing) no usa Tesseract: corre en este hilo mientras OCR trabaja.
            String qr = readQRCode(prep.std);

            gather(stageA, passes, deadline);
            Eval ev = evaluate(passes, qr);
            String stage = "A";

            if (ev.esReverso && qr == null) {
                qr = readQRCode(prep.flat);
                if (qr == null) qr = readQRCode(original);
                if (qr != null) ev = evaluate(passes, qr);
            }

            // ---------------- Etapa B: refuerzo solo si hace falta ----------------
            if (!isGood(ev)) {
                stage = "B";
                List<Future<Pass>> stageB = new ArrayList<>();
                if (!ev.esReverso) {
                    stageB.add(submit("BIN/AUTO", prep.bin, 1.0, null, PSM_AUTO));
                    stageB.add(submit("STD/SPARSE", prep.std, 1.0, null, PSM_SPARSE));
                    stageB.add(submit("FLAT/SPARSE", prep.flat, 1.0, null, PSM_SPARSE));
                    stageB.addAll(submitClaveCrops(prep, passes));
                    if (ev.confidence < 80) {
                        stageB.add(executor.submit(
                            () -> ocrPass("BIG/AUTO", prep.big(), 1.5, null, PSM_AUTO)));
                    }
                } else {
                    stageB.add(submit("STD/MRZ", prep.std, 1.0, MRZ_WL, PSM_SPARSE));
                    stageB.add(submit("FLAT/MRZ", prep.flat, 1.0, MRZ_WL, PSM_SPARSE));
                    stageB.add(submit("BIN/AUTO", prep.bin, 1.0, null, PSM_AUTO));
                }
                if (qr == null) qr = readQRCode(prep.bin);   // mientras trabajan los hilos
                gather(stageB, passes, deadline);
                ev = evaluate(passes, qr);
            }

            // ---------------- Etapa C: foto girada (último recurso) ----------------
            if (ev.nombre == null && ev.clave == null) {
                stage = "C";
                List<Future<Pass>> stageC = new ArrayList<>();
                for (final int deg : new int[] {90, 270, 180}) {
                    stageC.add(executor.submit(() -> ocrPass("ROT" + deg + "/AUTO",
                        Img.enhanceStd(Img.rotate(prep.gray, deg)), 1.0, null, PSM_AUTO)));
                }
                gather(stageC, passes, deadline);
                ev = evaluate(passes, qr);
            }

            boolean needsReview = ev.nombre == null
                || ev.nombreSupport < 2
                || ev.clave == null
                || !ev.claveValid
                || ev.claveSupport < 2
                || ev.confidence < 55
                || ev.cross.conflicto();   // la clave contradice la fecha impresa o el nombre

            long total = System.currentTimeMillis() - t0;
            log.info("scan OK etapa={} pasadas={} conf={} review={} total={}ms",
                stage, passes.size(), Math.round(ev.confidence), needsReview, total);
            log.debug("nombre='{}' clave='{}' (soporte nombre={}, clave={}) cross={}",
                ev.nombre, ev.clave, ev.nombreSupport, ev.claveSupport, ev.cross.notas);

            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("nombre", ev.nombre != null ? ev.nombre.trim() : "");
            fields.put("claveElector", ev.clave != null ? ev.clave.trim() : "");

            Map<String, Object> cross = new LinkedHashMap<>();
            cross.put("corregida", ev.cross.corregida);
            cross.put("letrasCorregidas", ev.cross.letrasCorregidas);
            cross.put("fechaImpresa", ev.cross.fechaImpresa);
            cross.put("fechaOk", ev.cross.fechaOk);
            cross.put("letrasOk", ev.cross.letrasOk);
            cross.put("confirmaciones", ev.cross.confirmaciones);
            cross.put("notas", ev.cross.notas);

            Map<String, Object> debug = new LinkedHashMap<>();
            debug.put("etapa", stage);
            debug.put("pasadas", passes.size());
            debug.put("ms", total);
            debug.put("cross", cross);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("rawText", ev.bestText == null ? "" : ev.bestText);
            payload.put("esReverso", ev.esReverso);
            payload.put("ocrConfidence", (int) Math.round(ev.confidence));
            payload.put("needsReview", needsReview);
            payload.put("claveValida", ev.claveValid);
            payload.put("fields", fields);
            payload.put("debug", debug);
            return ResponseEntity.ok(payload);

        } catch (Exception ex) {
            log.error("Error procesando imagen", ex);
            // Map.of NO acepta null: getMessage() puede ser null (NPE en el catch original).
            String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            return ResponseEntity.status(500).body(Map.of("error", "processing_error", "message", msg));
        }
    }

    private static BufferedImage readImage(MultipartFile file) throws Exception {
        try (InputStream in = file.getInputStream()) {
            BufferedImage img = ImageIO.read(in);
            if (img == null) {
                throw new IllegalArgumentException("No se pudo leer la imagen (usa JPG o PNG).");
            }
            return img;
        }
    }

    // =====================================================================
    // Pasadas de OCR
    // =====================================================================
    private Future<Pass> submit(final String name, final BufferedImage img, final double scale,
                                final String whitelist, final int psm) {
        return executor.submit(() -> ocrPass(name, img, scale, whitelist, psm));
    }

    private void gather(List<Future<Pass>> futures, List<Pass> sink, long deadline) {
        for (Future<Pass> f : futures) {
            long remaining = Math.max(1L, deadline - System.currentTimeMillis());
            try {
                Pass p = f.get(remaining, TimeUnit.MILLISECONDS);
                if (p != null) sink.add(p);
            } catch (TimeoutException e) {
                log.warn("TIMEOUT en una pasada OCR");
                f.cancel(true);
            } catch (ExecutionException e) {
                log.warn("Fallo en pasada OCR: {}", String.valueOf(e.getCause()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * UNA sola llamada a Tesseract por pasada: obtiene texto por línea + confianza + caja.
     */
    private Pass ocrPass(String name, BufferedImage img, double scale, String whitelist, int psm) {
        long t0 = System.currentTimeMillis();
        Tesseract t = null;
        try {
            t = pool.poll(10, TimeUnit.SECONDS);
            if (t == null) {
                log.warn("Pool vacío: instancia temporal");
                t = createTesseract();
            }
            t.setTessVariable("tessedit_char_whitelist", whitelist != null ? whitelist : "");
            t.setPageSegMode(psm);

            List<Line> lines = new ArrayList<>();
            StringBuilder sb = new StringBuilder();
            double confSum = 0;
            int chars = 0;

            List<Word> words = null;
            try {
                words = t.getWords(img, ITessAPI.TessPageIteratorLevel.RIL_TEXTLINE);
            } catch (Exception e) {
                log.debug("getWords falló ({}), uso doOCR", e.getMessage());
            }
            if (words != null) {
                for (Word w : words) {
                    String txt = cleanLine(w.getText());
                    if (txt.isEmpty()) continue;
                    double c = w.getConfidence();
                    lines.add(new Line(txt, c, w.getBoundingBox()));
                    sb.append(txt).append('\n');
                    confSum += c * txt.length();
                    chars += txt.length();
                }
            }

            String text;
            double conf;
            if (lines.isEmpty()) {
                String res = t.doOCR(img);
                text = res == null ? "" : res.replaceAll("[\\p{C}&&[^\\n]]", " ").trim();
                conf = 0;
            } else {
                text = sb.toString().trim();
                conf = chars > 0 ? confSum / chars : 0;
            }
            long ms = System.currentTimeMillis() - t0;
            log.debug("pasada {} psm={} conf={} chars={} {}ms", name, psm, Math.round(conf), text.length(), ms);
            return new Pass(name, scale, text, conf, lines, ms);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Pass.empty(name, scale);
        } catch (Exception e) {
            log.warn("Excepción en pasada {}: {}", name, e.toString());
            return Pass.empty(name, scale);
        } finally {
            if (t != null) pool.offer(t);
        }
    }

    private static String cleanLine(String s) {
        if (s == null) return "";
        return s.replaceAll("\\p{C}", " ").replaceAll("[ \\t]+", " ").trim();
    }

    /**
     * Re-OCR del recorte alrededor de "CLAVE DE ELECTOR".
     *
     * Antes: 2 recortes de la MISMA región con la MISMA imagen -> si el OCR confundía 9->1 en esos
     * píxeles el error se repetía en ambos y ganaba la votación (x1.5 de peso).
     * Ahora: UNA región (la línea de etiqueta con más confianza) leída con 3 variantes distintas
     * (imagen aplanada x2, imagen estándar x3, binarizada x2.5 con otro modo de segmentación),
     * para que los errores no estén correlacionados.
     */
    private List<Future<Pass>> submitClaveCrops(final Prepared prep, List<Pass> passes) {
        List<Future<Pass>> out = new ArrayList<>();
        Pass bp = null;
        Line bl = null;
        for (Pass p : passes) {
            for (Line ln : p.lines) {
                if (ln.box == null) continue;
                if (!IneParser.LABEL_PATTERN.matcher(IneParser.norm(ln.text)).find()) continue;
                if (bl == null || ln.conf > bl.conf) { bp = p; bl = ln; }
            }
        }
        if (bl == null) return out;

        double s = bp.scale <= 0 ? 1.0 : bp.scale;
        Rectangle b = bl.box;
        int bh = (int) Math.round(b.height / s);
        int x0 = Math.max(0, (int) Math.round(b.x / s) - 10);
        int y0 = Math.max(0, (int) Math.round(b.y / s) - (int) (bh * 0.6));
        int x1 = prep.flat.getWidth();
        int y1 = Math.min(prep.flat.getHeight(),
            (int) Math.round((b.y + b.height) / s) + (int) (bh * 1.6));
        if (x1 - x0 < 100 || y1 - y0 < 15) return out;

        final Rectangle area = new Rectangle(x0, y0, x1 - x0, y1 - y0);
        final BufferedImage[] src = {prep.flat, prep.std, prep.bin};
        final double[] factor = {2.0, 3.0, 2.5};
        final int[] psm = {PSM_SPARSE, PSM_SPARSE, PSM_BLOCK};
        for (int i = 0; i < 3; i++) {
            final int k = i;
            out.add(executor.submit(() -> ocrPass("CROP" + k + "/CLAVE",
                Img.cropScaled(src[k], area, factor[k], 12), 1.0, ALNUM_WL, psm[k])));
        }
        return out;
    }

    // =====================================================================
    // Evaluación: votación entre pasadas + validación cruzada
    // =====================================================================
    private static boolean isGood(Eval e) {
        return e.nombre != null && e.nombreSupport >= 2
            && e.clave != null && e.claveValid && e.claveSupport >= 2
            && !e.cross.conflicto();
    }

    private Eval evaluate(List<Pass> passes, String qrText) {
        Eval e = new Eval();

        boolean idmex = false, dobleLt = false, etiquetaFrente = false;
        for (Pass p : passes) {
            String u = IneParser.norm(p.text);
            if (IneParser.IDMEX_PATTERN.matcher(u).find()) idmex = true;
            if (u.contains("<<")) dobleLt = true;
            if (IneParser.hasNombreLabel(u) || IneParser.LABEL_PATTERN.matcher(u).find()) etiquetaFrente = true;
        }
        e.esReverso = idmex || (dobleLt && !etiquetaFrente);

        List<Cand> names = new ArrayList<>();
        List<Cand> claves = new ArrayList<>();
        IneCrossCheck.Evidence evidence = new IneCrossCheck.Evidence();
        double maxConf = 0, bestAutoConf = -1;

        for (Pass p : passes) {
            maxConf = Math.max(maxConf, p.conf);
            if (p.text.isEmpty()) continue;
            boolean crop = p.name.startsWith("CROP");
            double w = 0.4 + 0.6 * Math.min(100.0, Math.max(0.0, p.conf)) / 100.0;

            // Fecha impresa / CURP / sexo: evidencia independiente de la clave.
            if (!crop && !e.esReverso) evidence.add(p.text, w);

            if (!crop) {
                String n = e.esReverso
                    ? IneParser.extractNombreFromMrz(p.text)
                    : IneParser.extractNombreFrente(p.text);
                if (IneParser.plausibleName(n)) names.add(new Cand(n, w, 1));
            }
            if (!e.esReverso) {
                String c = IneParser.extractClave(p.text);
                if (c != null) {
                    // Los 3 recortes leen los mismos píxeles: pesan menos que una pasada independiente.
                    double cw = w * (IneParser.isValidClave(c) ? 1.0 : 0.4) * (crop ? 0.7 : 1.0);
                    claves.add(new Cand(c, cw, 1));
                }
            }
            if (p.name.endsWith("/AUTO") && p.conf > bestAutoConf) {
                bestAutoConf = p.conf;
                e.bestText = p.text;
            }
        }
        if (qrText != null) {
            String qc = IneParser.extractClaveFromCompact(qrText);
            if (qc != null) claves.add(new Cand(qc, 3.0, 2));   // el QR es la fuente más confiable
        }

        e.nombre = pickName(names, e);

        // 1) candidato por votación (exacta y carácter a carácter) + 2) arbitraje con la evidencia
        String voted = pickClave(claves, e);
        Map<String, Double> tally = new LinkedHashMap<>();
        for (Cand c : claves) tally.merge(c.v, c.w, Double::sum);
        if (voted != null) tally.merge(voted, 1.0, Double::sum);
        e.cross = IneCrossCheck.resolveClave(tally, evidence, e.nombre, e.nombreSupport);
        e.clave = e.cross.clave;
        e.claveSupport = countSupport(claves, e.clave);
        e.claveValid = IneParser.isValidClave(e.clave);
        e.confidence = bestAutoConf >= 0 ? bestAutoConf : maxConf;
        return e;
    }

    /** Elige el "medoide" ponderado: el candidato más parecido al resto. */
    private static String pickName(List<Cand> cs, Eval e) {
        e.nombreSupport = 0;
        if (cs.isEmpty()) return null;
        Cand best = null;
        double bestCost = Double.MAX_VALUE;
        for (Cand a : cs) {
            double cost = 0;
            for (Cand b : cs) cost += b.w * (1.0 - IneParser.similarity(a.v, b.v));
            boolean better;
            if (best == null) better = true;
            else if (cost < bestCost - 1e-9) better = true;
            else if (Math.abs(cost - bestCost) <= 1e-9) {
                better = a.w > best.w + 1e-9
                    || (Math.abs(a.w - best.w) <= 1e-9 && a.v.length() < best.v.length());
            } else better = false;
            if (better) { best = a; bestCost = cost; }
        }
        for (Cand c : cs) if (IneParser.similarity(best.v, c.v) >= 0.88) e.nombreSupport += c.votes;
        return best.v;
    }

    /** Voto por valor exacto y, con >=3 candidatos, voto carácter a carácter. */
    private static String pickClave(List<Cand> cs, Eval e) {
        e.claveSupport = 0;
        if (cs.isEmpty()) return null;

        Map<String, double[]> tally = new LinkedHashMap<>();   // valor -> [peso, votos]
        for (Cand c : cs) {
            double[] t = tally.computeIfAbsent(c.v, k -> new double[2]);
            t[0] += c.w;
            t[1] += c.votes;
        }
        String bestExact = null;
        double bestScore = -1;
        for (Map.Entry<String, double[]> en : tally.entrySet()) {
            double score = en.getValue()[0] + (IneParser.isValidClave(en.getKey()) ? 10 : 0);
            if (score > bestScore) { bestScore = score; bestExact = en.getKey(); }
        }

        String chosen = bestExact;
        List<String> same18 = new ArrayList<>();
        for (Cand c : cs) if (c.v.length() == 18) same18.add(c.v);
        if (same18.size() >= 3) {
            double[][] pos = new double[18][128];
            for (Cand c : cs) {
                if (c.v.length() != 18) continue;
                for (int k = 0; k < 18; k++) {
                    char ch = c.v.charAt(k);
                    if (ch < 128) pos[k][ch] += c.w;
                }
            }
            char[] out = new char[18];
            for (int k = 0; k < 18; k++) {
                int arg = 0;
                for (int ch = 1; ch < 128; ch++) if (pos[k][ch] > pos[k][arg]) arg = ch;
                out[k] = (char) arg;
            }
            String voted = new String(out);
            if (IneParser.isValidClave(voted)) chosen = voted;
        }

        e.claveSupport = countSupport(cs, chosen);
        return chosen;
    }

    /** Votos que respaldan 'chosen': exactos, o casi iguales (>=16 de 18 posiciones). */
    private static int countSupport(List<Cand> cs, String chosen) {
        if (chosen == null) return 0;
        int n = 0;
        for (Cand c : cs) {
            if (c.v.equals(chosen)) n += c.votes;
            else if (c.v.length() == 18 && chosen.length() == 18
                     && IneParser.hammingAgree(c.v, chosen) >= 16) n += 1;
        }
        return n;
    }

    // =====================================================================
    // QR
    // =====================================================================
    private String readQRCode(BufferedImage image) {
        if (image == null) return null;
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        try {
            BufferedImageLuminanceSource src = new BufferedImageLuminanceSource(image);
            try {
                return new MultiFormatReader()
                    .decode(new BinaryBitmap(new HybridBinarizer(src)), hints).getText();
            } catch (Exception first) {
                return new MultiFormatReader()
                    .decode(new BinaryBitmap(new GlobalHistogramBinarizer(src)), hints).getText();
            }
        } catch (Exception e) {
            return null;
        }
    }

    // =====================================================================
    // Modelos internos
    // =====================================================================
    private static final class Line {
        final String text;
        final double conf;
        final Rectangle box;
        Line(String text, double conf, Rectangle box) { this.text = text; this.conf = conf; this.box = box; }
    }

    private static final class Pass {
        final String name;
        final double scale;
        final String text;
        final double conf;
        final List<Line> lines;
        final long ms;
        Pass(String name, double scale, String text, double conf, List<Line> lines, long ms) {
            this.name = name; this.scale = scale; this.text = text; this.conf = conf;
            this.lines = lines; this.ms = ms;
        }
        static Pass empty(String name, double scale) {
            return new Pass(name, scale, "", 0, new ArrayList<Line>(), 0);
        }
    }

    private static final class Cand {
        final String v;
        final double w;
        final int votes;
        Cand(String v, double w, int votes) { this.v = v; this.w = w; this.votes = votes; }
    }

    private static final class Eval {
        boolean esReverso;
        String nombre;
        String clave;
        boolean claveValid;
        int nombreSupport;
        int claveSupport;
        double confidence;
        String bestText;
        IneCrossCheck.Result cross = new IneCrossCheck.Result();
    }

    private static final class Prepared {
        final BufferedImage gray;   // base normalizada + deskew
        final BufferedImage std;    // autocontraste + sharpen suave
        final BufferedImage flat;   // iluminación aplanada (sombras / luz dispareja)
        final BufferedImage bin;    // binarización adaptativa
        private BufferedImage big;  // x1.5, solo si hace falta (imágenes borrosas)

        Prepared(BufferedImage gray, BufferedImage std, BufferedImage flat, BufferedImage bin) {
            this.gray = gray; this.std = std; this.flat = flat; this.bin = bin;
        }

        synchronized BufferedImage big() {
            if (big == null) big = Img.upscaleSharp(flat, 1.5);
            return big;
        }

        static Prepared build(BufferedImage original) {
            BufferedImage rgb = Img.resizeToTarget(original, TARGET_LONG_SIDE);
            BufferedImage gray = Img.toGray(rgb);
            double angle = Img.detectSkew(gray);
            if (Math.abs(angle) >= MIN_SKEW_DEG && Math.abs(angle) <= MAX_SKEW_DEG) {
                gray = Img.rotate(gray, -angle);
            }
            BufferedImage[] fb = Img.flatAndBinary(gray);
            return new Prepared(gray, Img.enhanceStd(gray), Img.unsharp(fb[0], 0.6f), fb[1]);
        }
    }

    // =====================================================================
    // Procesamiento de imagen (todo sobre byte[] — sin getRGB/setRGB por píxel)
    // =====================================================================
    static final class Img {
        private Img() {}

        static byte[] data(BufferedImage gray) {
            byte[] d = ((DataBufferByte) gray.getRaster().getDataBuffer()).getData();
            if (d.length != gray.getWidth() * gray.getHeight()) {
                throw new IllegalStateException("Raster gris con stride inesperado");
            }
            return d;
        }

        static BufferedImage newGray(int w, int h) {
            return new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        }

        static BufferedImage resize(BufferedImage src, int w, int h, Object interpolation, int type) {
            BufferedImage out = new BufferedImage(Math.max(1, w), Math.max(1, h), type);
            Graphics2D g = out.createGraphics();
            try {
                g.setColor(Color.WHITE);   // evita fondo negro en PNG con transparencia
                g.fillRect(0, 0, out.getWidth(), out.getHeight());
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.drawImage(src, 0, 0, out.getWidth(), out.getHeight(), null);
            } finally {
                g.dispose();
            }
            return out;
        }

        /**
         * Normaliza el lado largo a 'target'. Reducción por mitades = sin aliasing.
         */
        static BufferedImage resizeToTarget(BufferedImage src, int target) {
            int w = src.getWidth(), h = src.getHeight();
            double scale = Math.min(3.0, (double) target / Math.max(w, h));
            int tw = Math.max(1, (int) Math.round(w * scale));
            int th = Math.max(1, (int) Math.round(h * scale));
            BufferedImage cur = src;
            while (cur.getWidth() / 2 >= tw && cur.getHeight() / 2 >= th) {
                cur = resize(cur, cur.getWidth() / 2, cur.getHeight() / 2,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR, BufferedImage.TYPE_INT_RGB);
            }
            Object hint = scale > 1.0 ? RenderingHints.VALUE_INTERPOLATION_BICUBIC
                                      : RenderingHints.VALUE_INTERPOLATION_BILINEAR;
            return resize(cur, tw, th, hint, BufferedImage.TYPE_INT_RGB);
        }

        /** Luminancia directa (evita el desvío de gamma de dibujar RGB sobre TYPE_BYTE_GRAY). */
        static BufferedImage toGray(BufferedImage rgb) {
            int w = rgb.getWidth(), h = rgb.getHeight();
            BufferedImage gray = newGray(w, h);
            byte[] out = data(gray);
            int[] row = new int[w];
            for (int y = 0; y < h; y++) {
                rgb.getRGB(0, y, w, 1, row, 0, w);
                int base = y * w;
                for (int x = 0; x < w; x++) {
                    int p = row[x];
                    int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                    out[base + x] = (byte) ((r * 299 + g * 587 + b * 114) / 1000);
                }
            }
            return gray;
        }

        /** Ángulo de inclinación estimado sobre una copia reducida y BINARIA (jdeskew necesita blanco/negro). */
        static double detectSkew(BufferedImage gray) {
            try {
                BufferedImage small = gray;
                if (gray.getWidth() > 900) {
                    small = resize(gray, gray.getWidth() / 2, gray.getHeight() / 2,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR, BufferedImage.TYPE_BYTE_GRAY);
                }
                BufferedImage bin = flatAndBinary(small)[1];
                return new ImageDeskew(bin).getSkewAngle();
            } catch (Exception e) {
                return 0;
            }
        }

        /** Rota con lienzo ampliado y fondo BLANCO (antes las esquinas quedaban negras y Tesseract leía basura). */
        static BufferedImage rotate(BufferedImage src, double deg) {
            double rad = Math.toRadians(deg);
            double sin = Math.abs(Math.sin(rad)), cos = Math.abs(Math.cos(rad));
            int w = (int) Math.ceil(src.getWidth() * cos + src.getHeight() * sin - 1e-6);
            int h = (int) Math.ceil(src.getWidth() * sin + src.getHeight() * cos - 1e-6);
            BufferedImage out = newGray(w, h);
            Graphics2D g = out.createGraphics();
            try {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, w, h);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                AffineTransform at = new AffineTransform();
                at.translate(w / 2.0, h / 2.0);
                at.rotate(rad);
                at.translate(-src.getWidth() / 2.0, -src.getHeight() / 2.0);
                g.drawImage(src, at, null);
            } finally {
                g.dispose();
            }
            return out;
        }

        /**
         * Una sola pasada con imagen integral: devuelve
         *  [0] gris con iluminación aplanada (divide entre la media local)
         *  [1] binarización adaptativa tipo Bradley (robusta a sombras / luz dispareja)
         */
        static BufferedImage[] flatAndBinary(BufferedImage gray) {
            int w = gray.getWidth(), h = gray.getHeight();
            byte[] g = data(gray);
            int W = w + 1;
            int[] integral = new int[W * (h + 1)];
            for (int y = 0; y < h; y++) {
                int rowSum = 0;
                int base = y * w;
                for (int x = 0; x < w; x++) {
                    rowSum += g[base + x] & 0xFF;
                    integral[(y + 1) * W + (x + 1)] = integral[y * W + (x + 1)] + rowSum;
                }
            }
            int s = Math.max(25, Math.min(w, h) / 10);
            BufferedImage flat = newGray(w, h), bin = newGray(w, h);
            byte[] f = data(flat), b = data(bin);
            for (int y = 0; y < h; y++) {
                int y0 = Math.max(0, y - s), y1 = Math.min(h - 1, y + s);
                for (int x = 0; x < w; x++) {
                    int x0 = Math.max(0, x - s), x1 = Math.min(w - 1, x + s);
                    int count = (x1 - x0 + 1) * (y1 - y0 + 1);
                    long sum = (long) integral[(y1 + 1) * W + (x1 + 1)] - integral[y0 * W + (x1 + 1)]
                        - integral[(y1 + 1) * W + x0] + integral[y0 * W + x0];
                    double mean = Math.max(1.0, (double) sum / count);
                    int idx = y * w + x;
                    int v = g[idx] & 0xFF;
                    int fv = (int) Math.round(v * 230.0 / mean);
                    f[idx] = (byte) (fv > 255 ? 255 : fv);
                    b[idx] = (byte) (v < mean * 0.85 ? 0 : 255);
                }
            }
            return new BufferedImage[] {flat, bin};
        }

        /** Estiramiento de contraste por percentiles (1%-99%), adaptativo a la foto. */
        static void autoLevels(byte[] px) {
            int[] hist = new int[256];
            for (byte v : px) hist[v & 0xFF]++;
            int cut = px.length / 100;
            int lo = 0, acc = 0;
            while (lo < 255 && acc + hist[lo] <= cut) { acc += hist[lo]; lo++; }
            int hi = 255;
            acc = 0;
            while (hi > 0 && acc + hist[hi] <= cut) { acc += hist[hi]; hi--; }
            if (hi - lo < 40) return;   // imagen casi plana: estirar solo amplificaría ruido
            int[] lut = new int[256];
            for (int i = 0; i < 256; i++) {
                int v = (int) Math.round((i - lo) * 255.0 / (hi - lo));
                lut[i] = v < 0 ? 0 : (v > 255 ? 255 : v);
            }
            for (int i = 0; i < px.length; i++) px[i] = (byte) lut[px[i] & 0xFF];
        }

        static BufferedImage copy(BufferedImage gray) {
            BufferedImage c = newGray(gray.getWidth(), gray.getHeight());
            System.arraycopy(data(gray), 0, data(c), 0, gray.getWidth() * gray.getHeight());
            return c;
        }

        static BufferedImage enhanceStd(BufferedImage gray) {
            BufferedImage c = copy(gray);
            autoLevels(data(c));
            return unsharp(c, 0.7f);
        }

        static BufferedImage upscaleSharp(BufferedImage gray, double factor) {
            BufferedImage up = resize(gray, (int) Math.round(gray.getWidth() * factor),
                (int) Math.round(gray.getHeight() * factor),
                RenderingHints.VALUE_INTERPOLATION_BICUBIC, BufferedImage.TYPE_BYTE_GRAY);
            return unsharp(up, 0.8f);
        }

        /** Unsharp mask suave (el kernel 3x3 de "5 al centro" amplificaba el ruido y metía letras falsas). */
        static BufferedImage unsharp(BufferedImage gray, float amount) {
            int w = gray.getWidth(), h = gray.getHeight();
            byte[] s = data(gray);
            byte[] bl = blur3(s, w, h);
            BufferedImage out = newGray(w, h);
            byte[] o = data(out);
            for (int i = 0; i < s.length; i++) {
                int v = s[i] & 0xFF;
                int r = Math.round(v + amount * (v - (bl[i] & 0xFF)));
                o[i] = (byte) (r < 0 ? 0 : (r > 255 ? 255 : r));
            }
            return out;
        }

        private static byte[] blur3(byte[] src, int w, int h) {
            int[] tmp = new int[w * h];
            for (int y = 0; y < h; y++) {
                int base = y * w;
                for (int x = 0; x < w; x++) {
                    int l = src[base + (x > 0 ? x - 1 : 0)] & 0xFF;
                    int c = src[base + x] & 0xFF;
                    int r = src[base + (x < w - 1 ? x + 1 : x)] & 0xFF;
                    tmp[base + x] = l + 2 * c + r;
                }
            }
            byte[] out = new byte[w * h];
            for (int y = 0; y < h; y++) {
                int up = (y > 0 ? y - 1 : 0) * w, mid = y * w, dn = (y < h - 1 ? y + 1 : y) * w;
                for (int x = 0; x < w; x++) {
                    out[mid + x] = (byte) ((tmp[up + x] + 2 * tmp[mid + x] + tmp[dn + x] + 8) >> 4);
                }
            }
            return out;
        }

        static BufferedImage cropScaled(BufferedImage src, Rectangle area, double factor, int pad) {
            Rectangle r = area.intersection(new Rectangle(0, 0, src.getWidth(), src.getHeight()));
            if (r.width <= 0 || r.height <= 0) return src;
            BufferedImage sub = src.getSubimage(r.x, r.y, r.width, r.height);
            int w = (int) Math.round(r.width * factor), h = (int) Math.round(r.height * factor);
            BufferedImage out = newGray(w + 2 * pad, h + 2 * pad);
            Graphics2D g = out.createGraphics();
            try {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, out.getWidth(), out.getHeight());
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.drawImage(sub, pad, pad, w, h, null);
            } finally {
                g.dispose();
            }
            return out;
        }
    }

    // =====================================================================
    // Parser de INE: texto OCR -> nombre / clave de elector
    // =====================================================================
    static final class IneParser {
        private IneParser() {}

        static final Pattern LABEL_PATTERN = Pattern.compile(
            "CLAVE\\s*(?:DE\\s*)?(?:ELECTOR|LECTOR|LLECTOR|ELER|ELCTOR|ELECT\\w*)",
            Pattern.CASE_INSENSITIVE);
        static final Pattern IDMEX_PATTERN = Pattern.compile("[I1L]DMEX");
        private static final Pattern CLAVE_STRICT = Pattern.compile("[A-Z]{6}\\d{8}[HM]\\d{3}");

        /**
         * Palabras de etiqueta que cortan el nombre. Se comparan como TOKEN COMPLETO.
         */
        static final Set<String> STOP_LABELS = new HashSet<>(Arrays.asList(
            "DOMICILIO", "CLAVE", "ELECTOR", "CURP", "FECHA", "NACIMIENTO", "SECCION", "VIGENCIA",
            "ANO", "AÑO", "REGISTRO", "FOLIO", "INSTITUTO", "NACIONAL", "ELECTORAL", "CREDENCIAL",
            "VOTAR", "MEXICO", "MUNICIPIO", "ESTADO", "LOCALIDAD", "EMISION", "CALLE", "COLONIA",
            "FRACC", "DELEGACION", "ALCALDIA"));
        static final Set<String> PARTICLES = new HashSet<>(Arrays.asList("DE", "DEL", "LA", "LAS", "LOS", "MC", "VAN", "VON"));

        // ---------------------------------------------------------- utilidades
        static String norm(String s) {
            if (s == null) return "";
            String t = s.toUpperCase(Locale.ROOT).replace('Ñ', '\u0001');
            t = Normalizer.normalize(t, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
            return t.replace('\u0001', 'Ñ');
        }

        static int lev(String a, String b) {
            int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
            for (int j = 0; j <= b.length(); j++) prev[j] = j;
            for (int i = 1; i <= a.length(); i++) {
                cur[0] = i;
                for (int j = 1; j <= b.length(); j++) {
                    int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                    cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                }
                int[] t = prev; prev = cur; cur = t;
            }
            return prev[b.length()];
        }

        static double similarity(String a, String b) {
            if (a == null || b == null) return 0;
            int m = Math.max(a.length(), b.length());
            return m == 0 ? 1.0 : 1.0 - (double) lev(a, b) / m;
        }

        static int hammingAgree(String a, String b) {
            int n = 0;
            for (int i = 0; i < Math.min(a.length(), b.length()); i++) if (a.charAt(i) == b.charAt(i)) n++;
            return n;
        }

        private static List<String> tokens(String line) {
            List<String> out = new ArrayList<>();
            for (String t : norm(line).split("[^A-ZÑ0-9]+")) if (!t.isEmpty()) out.add(t);
            return out;
        }

        static boolean isStopLabel(String tok) {
            if (STOP_LABELS.contains(tok)) return true;
            if (tok.length() < 6) return false;
            for (String l : STOP_LABELS) {
                if (l.length() < 6 || Math.abs(l.length() - tok.length()) > 2) continue;
                if (lev(tok, l) <= (l.length() >= 9 ? 2 : 1)) return true;
            }
            return false;
        }

        static boolean isNombreLabel(String tok) {
            if (tok.equals("NOMBRE")) return true;
            return tok.length() >= 5 && tok.length() <= 7 && lev(tok, "NOMBRE") <= 1;
        }

        static boolean hasNombreLabel(String normText) {
            for (String t : normText.split("[^A-ZÑ0-9]+")) if (isNombreLabel(t)) return true;
            return false;
        }

        // ------------------------------------------------------------- NOMBRE
        private static String fixDigits(String tk) {
            long letters = tk.chars().filter(Character::isLetter).count();
            if (letters * 10 < (long) tk.length() * 6) return tk;     // casi todo dígitos: no tocar
            StringBuilder sb = new StringBuilder(tk.length());
            for (char c : tk.toCharArray()) {
                switch (c) {
                    case '0': sb.append('O'); break;
                    case '1': sb.append('I'); break;
                    case '5': sb.append('S'); break;
                    case '8': sb.append('B'); break;
                    case '6': sb.append('G'); break;
                    case '2': sb.append('Z'); break;
                    default: sb.append(c);
                }
            }
            return sb.toString();
        }

        private static boolean hasVowel(String t) {
            for (char c : t.toCharArray()) if ("AEIOU".indexOf(c) >= 0) return true;
            return false;
        }

        private static boolean plausibleNameToken(String t) {
            if (t.length() < 2) return false;
            if (t.length() == 2) return PARTICLES.contains(t) || hasVowel(t);
            return hasVowel(t);
        }

        private static boolean looksLikeSexo(String t) {
            return t.equals("SEXO") || (t.length() == 4 && lev(t, "SEXO") <= 1);
        }

        /** flags[0]=encontró etiqueta de otra sección, flags[1]=había basura numérica */
        private static List<String> nameTokens(List<String> raw, boolean[] flags) {
            List<String> out = new ArrayList<>();
            for (int k = 0; k < raw.size(); k++) {
                String fixed = fixDigits(raw.get(k));
                if (looksLikeSexo(fixed)) {
                    if (k + 1 < raw.size() && raw.get(k + 1).length() == 1) k++;   // "SEXO H"
                    continue;
                }
                if (isStopLabel(fixed)) { flags[0] = true; break; }
                if (fixed.matches(".*\\d.*")) { flags[1] = true; continue; }
                fixed = fixed.replaceAll("(.)\\1{2,}", "$1$1");                   // AAAARON -> AARON
                if (!plausibleNameToken(fixed)) continue;
                out.add(fixed);
            }
            return out;
        }

        static String extractNombreFrente(String text) {
            if (text == null || text.isBlank()) return null;
            String[] lines = text.split("\\r?\\n");
            for (int i = 0; i < lines.length; i++) {
                List<String> toks = tokens(lines[i]);
                int li = -1;
                for (int k = 0; k < toks.size(); k++) {
                    if (isNombreLabel(toks.get(k))) { li = k; break; }
                }
                if (li < 0) continue;

                boolean[] flags = new boolean[2];
                List<String> parts = new ArrayList<>(nameTokens(toks.subList(li + 1, toks.size()), flags));
                int fragments = parts.isEmpty() ? 0 : 1;

                if (!flags[0]) {
                    for (int j = i + 1; j < lines.length && j <= i + 6 && fragments < 4; j++) {
                        List<String> raw = tokens(lines[j]);
                        if (raw.isEmpty()) continue;
                        boolean[] fl = new boolean[2];
                        List<String> nt = nameTokens(raw, fl);
                        if (!nt.isEmpty()) { parts.addAll(nt); fragments++; }
                        if (fl[0] || (fl[1] && nt.isEmpty())) break;   // DOMICILIO / dirección
                    }
                }
                if (parts.size() >= 2) return String.join(" ", parts);
            }
            return rescueNombre(text);
        }

        /** Plan B cuando las líneas salen mezcladas: NOMBRE ... (DOMICILIO|CLAVE|CURP) en un solo bloque. */
        private static String rescueNombre(String text) {
            Matcher m = Pattern.compile("NOMBRE\\W*(.*?)(?:DOMICILIO|CLAVE|CURP)", Pattern.DOTALL)
                .matcher(norm(text));
            if (!m.find()) return null;
            List<String> raw = new ArrayList<>();
            for (String t : m.group(1).split("[^A-ZÑ0-9]+")) if (!t.isEmpty()) raw.add(t);
            List<String> nt = nameTokens(raw, new boolean[2]);
            return nt.size() >= 2 ? String.join(" ", nt) : null;
        }

        static boolean plausibleName(String n) {
            if (n == null) return false;
            String t = n.trim();
            if (t.length() < 5 || t.length() > 80) return false;
            int words = t.split("\\s+").length;
            return words >= 2 && words <= 8;
        }

        // ----------------------------------------------------------------- MRZ

        static String extractNombreFromMrz(String text) {
            if (text == null) return null;
            String best = null;
            int bestScore = 0;
            for (String line : text.split("\\r?\\n")) {
                String clean = norm(line).replaceAll("[^A-Z0-9<]", "");
                if (clean.length() < 8 || !clean.contains("<<") || clean.startsWith("IDMEX")) continue;
                long digits = clean.chars().filter(Character::isDigit).count();
                long letters = clean.chars().filter(Character::isLetter).count();
                if (digits > letters) continue;
                int score = (int) (letters - digits) + 10;
                if (score > bestScore) { bestScore = score; best = clean; }
            }
            String name = null;
            if (best != null) {
                name = parseMrzNameLine(best);
            } else {
                String flat = norm(text).replaceAll("[^A-Z<]", "");
                Matcher m = Pattern.compile("([A-Z]{2,})<([A-Z]{2,})<<([A-Z][A-Z<]{2,})").matcher(flat);
                if (m.find()) name = m.group(1) + " " + m.group(2) + " " + m.group(3).replace("<", " ");
            }
            return cleanMrzName(name);
        }

        private static String parseMrzNameLine(String mrzLine) {
            String[] parts = mrzLine.replaceAll("<+$", "").split("<<", 2);
            String apellidos = parts[0].replace("<", " ");
            String nombres = parts.length > 1 ? parts[1].replace("<", " ") : "";
            return (apellidos + " " + nombres).replaceAll("\\s+", " ").trim();
        }

        /** El relleno '<' del MRZ suele leerse como KKKK / CCCC / LLLL al final. */
        private static String cleanMrzName(String name) {
            if (name == null) return null;
            List<String> out = new ArrayList<>();
            for (String t : name.split("\\s+")) {
                String x = fixDigits(t).replaceAll("(K{3,}|C{3,}|L{3,}|S{3,}|E{3,}|I{3,})$", "");
                if (plausibleNameToken(x)) out.add(x);
            }
            return out.size() >= 2 ? String.join(" ", out) : null;
        }

        // ------------------------------------------------------ CLAVE DE ELECTOR
        /**
         * 6 letras + AAMMDD + estado(2) + H/M + 3 dígitos, con fecha y estado coherentes
         * Y EDAD PLAUSIBLE (17-105 años). Antes no se revisaba el año: "15070229" (11 ó 111 años)
         * pasaba igual que "95070229".
         */
        static boolean isValidClave(String c) {
            return IneCrossCheck.claveOk(c);
        }

        private static char toLetter(char c) {
            if (c >= 'A' && c <= 'Z') return c;
            switch (c) {
                case '0': return 'O';
                case '1': return 'I';
                case '2': return 'Z';
                case '5': return 'S';
                case '6': return 'G';
                case '8': return 'B';
                default: return 0;
            }
        }

        private static char toDigit(char c) {
            if (c >= '0' && c <= '9') return c;
            switch (c) {
                case 'O': case 'Q': case 'D': return '0';
                case 'I': case 'L': return '1';
                case 'Z': return '2';
                case 'S': return '5';
                case 'G': return '6';
                case 'B': return '8';
                default: return 0;
            }
        }

        /**
         * Ventana deslizante de 18 caracteres: cada posición se corrige según su tipo
         * (letra/dígito) y se elige la de MENOR número de correcciones que sea válida.
         * Tolera basura antes/después de la clave y confusiones O/0, I/1, S/5, B/8, Z/2, G/6
         * (hasta 6 correcciones si hay etiqueta, 4 si no; igual debe pasar la validación de fecha/estado/sexo).
         */
        private static String bestWindow(String s, boolean allowStructural) {
            String bestValid = null, bestStruct = null;
            int vCost = 99, sCost = 99;
            for (int i = 0; i + 18 <= s.length(); i++) {
                char[] out = new char[18];
                int cost = 0;
                boolean ok = true;
                for (int k = 0; k < 18 && ok; k++) {
                    char c = s.charAt(i + k);
                    char r = (k <= 5 || k == 14) ? toLetter(c) : toDigit(c);
                    if (r == 0) ok = false;
                    else { if (r != c) cost++; out[k] = r; }
                }
                if (!ok || cost > (allowStructural ? 6 : 4)) continue;
                if (out[14] != 'H' && out[14] != 'M') continue;
                String cand = new String(out);
                if (isValidClave(cand)) {
                    if (cost < vCost) { vCost = cost; bestValid = cand; }
                } else if (allowStructural && cost < sCost) {
                    sCost = cost; bestStruct = cand;
                }
            }
            return bestValid != null ? bestValid : bestStruct;
        }

        static String extractClave(String text) {
            if (text == null) return null;
            String[] lines = norm(text).split("\\r?\\n");
            String structural = null;
            for (int i = 0; i < lines.length; i++) {
                Matcher m = LABEL_PATTERN.matcher(lines[i]);
                if (!m.find()) continue;
                StringBuilder sb = new StringBuilder(lines[i].substring(m.end()));
                for (int k = 1; k <= 2 && i + k < lines.length; k++) sb.append(' ').append(lines[i + k]);
                String c = bestWindow(sb.toString().replaceAll("[^A-Z0-9]", ""), true);
                if (c != null) {
                    if (isValidClave(c)) return c;
                    if (structural == null) structural = c;
                }
            }
            for (String line : lines) {   // sin etiqueta legible: solo candidatos 100% válidos
                String compact = line.replaceAll("[^A-Z0-9]", "");
                if (compact.length() >= 18) {
                    String c = bestWindow(compact, false);
                    if (c != null) return c;
                }
            }
            return structural;
        }

        static String extractClaveFromCompact(String text) {
            if (text == null) return null;
            return bestWindow(norm(text).replaceAll("[^A-Z0-9]", ""), false);
        }
    }
}