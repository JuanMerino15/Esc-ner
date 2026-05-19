package com.juan.siam.springboot.scanner.scanner.controllers;

import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import net.sourceforge.tess4j.ITessAPI;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;

import com.recognition.software.jdeskew.ImageDeskew;
import org.apache.commons.io.FilenameUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.awt.image.BufferedImageOp;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@CrossOrigin(origins = "http://localhost:5173")
@RestController
@RequestMapping("/api")
public class OcrController {

    // -----------------------------------------------------------------------
    // CAMBIO 1: Pool de 3 instancias Tesseract (una por hilo)
    // Tesseract NO es thread-safe, por eso necesitamos instancias separadas
    // -----------------------------------------------------------------------
    // CAMBIO 1: Pool de 9 instancias (3 pasadas × 3 variantes = 9 simultáneas)
private static final int POOL_SIZE = 9;
private final BlockingQueue<Tesseract> tesseractPool = new ArrayBlockingQueue<>(POOL_SIZE);
private final ExecutorService executor = Executors.newFixedThreadPool(POOL_SIZE);

    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
        "DOMICILIO", "CLAVE", "CURP", "FECHA", "SECCION", "VIGENCIA",
        "AÑO", "REGISTRO", "FOLIO", "INSTITUTO", "ELECTORAL",
        "CREDENCIAL", "VOTAR", "MEXICO", "MUNICIPIO", "ESTADO", "FRACC",
        "COLONIA", "CP", "DELEGACION", "ALCALDIA", "AV", "CALLE",
        "HUAMANTLA", "TLAX", "NUM", "NO.", "INTERIOR"
    ));

    private static final Pattern LABEL_PATTERN = Pattern.compile(
        "CLAVE\\s*(?:DE\\s*)?(?:ELECTOR|LECTOR|LLECTOR|ELER|ELCTOR)",
        Pattern.CASE_INSENSITIVE
    );

    // -----------------------------------------------------------------------
    // CAMBIO 2: Constructor crea 3 instancias en lugar de 1
    // -----------------------------------------------------------------------
    public OcrController() {
        for (int i = 0; i < POOL_SIZE; i++) {
            tesseractPool.offer(createTesseractInstance());
        }

        File trained = new File("C:/tessdata_best/spa.traineddata");
        if (!trained.exists()) {
            System.err.println("ERROR: No se encontró spa.traineddata en C:/tessdata_best.");
        }
    }

    private Tesseract createTesseractInstance() {
        Tesseract t = new Tesseract();
        t.setDatapath("C:/tessdata_best");
        t.setLanguage("spa");
        try {
            t.setOcrEngineMode(ITessAPI.TessOcrEngineMode.OEM_LSTM_ONLY);
            t.setTessVariable("user_defined_dpi", "300");
            t.setTessVariable("tessedit_thresholding_method", "sauvola");
        } catch (Exception ignored) {}
        return t;
    }

    // -----------------------------------------------------------------------
    // POST /api/scan  — lógica IDÉNTICA a la original
    // -----------------------------------------------------------------------
    @PostMapping("/scan")
    public ResponseEntity<?> scan(@RequestParam("file") MultipartFile file) {

        File trained = new File("C:/tessdata_best/spa.traineddata");
        if (!trained.exists()) {
            return ResponseEntity.status(500).body(Map.of(
                "error", "missing_traineddata",
                "message", "No se encontró spa.traineddata en C:/tessdata_best"));
        }

        Path tmp = null;
        try {
            String ext = FilenameUtils.getExtension(
                Objects.requireNonNull(file.getOriginalFilename()));
            tmp = Files.createTempFile("upload-", "." + (ext.isEmpty() ? "jpg" : ext));
            file.transferTo(tmp.toFile());

            BufferedImage original = ImageIO.read(tmp.toFile());
            if (original == null) throw new RuntimeException("No se pudo leer la imagen subida.");

            List<BufferedImage> variants = preprocessVariants(original);
            BufferedImage primaryVariant = variants.get(0);

            // CAMBIO 3: Las 3 pasadas OCR ahora corren en paralelo
            String rawText   = ocrBestOf(variants, null,
                ITessAPI.TessPageSegMode.PSM_AUTO, false);
            if (rawText == null) rawText = "";

            String rawAlnum  = ocrBestOf(variants,
                "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 ",
                ITessAPI.TessPageSegMode.PSM_AUTO, true);
            if (rawAlnum == null) rawAlnum = "";

            String rawSparse = ocrBestOf(variants, null,
                ITessAPI.TessPageSegMode.PSM_SPARSE_TEXT, false);
            if (rawSparse == null) rawSparse = "";

            // QR — igual que antes
            String qrText = null;
            try {
                qrText = readQRCode(primaryVariant);
                if (qrText == null) qrText = readQRCode(original);
            } catch (Exception e) {
                System.err.println("QR read error: " + e.getMessage());
            }

            System.out.println("=== RAW TEXT ===\n"   + rawText);
            System.out.println("=== RAW ALNUM ===\n"  + rawAlnum);
            System.out.println("=== RAW SPARSE ===\n" + rawSparse);
            System.out.println("=== QR ===\n"         + qrText);

            // Todo lo de abajo es IDÉNTICO a tu versión original
            boolean esReverso = looksLikeBack(rawText)
                || looksLikeBack(rawAlnum)
                || looksLikeBack(rawSparse);

            String nombre       = null;
            String claveElector = null;

            if (esReverso) {
                for (String t : Arrays.asList(rawText, rawAlnum, rawSparse)) {
                    if (nombre == null) nombre = extractNombreFromMrz(t);
                }
                if (qrText != null) claveElector = extractClaveElectorRegex(qrText);

            } else {
                for (String t : Arrays.asList(rawText, rawAlnum, rawSparse)) {
                    if (nombre == null) nombre = extractNombreFrente(t);
                }
                if (nombre == null || nombre.isBlank()) {
                    nombre = rescueNombreFromFragments(rawText, rawAlnum, rawSparse);
                }

                for (String t : Arrays.asList(rawText, rawAlnum, rawSparse)) {
                    if (claveElector == null) claveElector = extractClaveElectorByLabel(t);
                }
                if (claveElector == null && qrText != null) {
                    claveElector = extractClaveElectorRegex(qrText);
                }
            }

            int confidence = calculateNativeConfidence(primaryVariant);

            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("nombre",       nombre       != null ? nombre.trim()       : "");
            fields.put("claveElector", claveElector != null ? claveElector.trim() : "");

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("rawText",       rawText);
            payload.put("rawAlnum",      rawAlnum);
            payload.put("rawSparse",     rawSparse);
            payload.put("esReverso",     esReverso);
            payload.put("ocrConfidence", confidence);
            payload.put("fields",        fields);

            return ResponseEntity.ok(payload);

        } catch (TesseractException e) {
            return ResponseEntity.status(500).body(
                Map.of("error", "tesseract_error", "message", e.getMessage()));
        } catch (Exception ex) {
            return ResponseEntity.status(500).body(
                Map.of("error", "processing_error", "message", ex.getMessage()));
        } finally {
            if (tmp != null) try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
        }
    }

    // =======================================================================
    // CAMBIO 4: ocrBestOf ahora lanza las 3 variantes EN PARALELO
    // Las 3 instancias del pool trabajan simultáneamente
    // =======================================================================
    private String ocrBestOf(List<BufferedImage> variants, String whitelist,
                              int psm, boolean suppressDawgs) throws TesseractException {

        List<Future<String>> futures = new ArrayList<>();

        for (BufferedImage variant : variants) {
            futures.add(executor.submit(() -> ocrPass(variant, whitelist, psm, suppressDawgs)));
        }

        String best = "";
       for (Future<String> future : futures) {
    try {
        String result = future.get(30, TimeUnit.SECONDS);
        if (result != null && result.length() > best.length()) best = result;
    } catch (TimeoutException e) {
        System.err.println("!!! TIMEOUT en variante OCR");
        future.cancel(true);
    } catch (ExecutionException e) {
        // *** ESTE ES EL IMPORTANTE — muestra la causa raíz ***
        System.err.println("!!! ExecutionException: " + e.getCause());
        e.getCause().printStackTrace();
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
}
        return best;
    }

    // -----------------------------------------------------------------------
    // CAMBIO 5: ocrPass usa el pool en lugar de la instancia única
    // -----------------------------------------------------------------------
   private String ocrPass(BufferedImage img, String whitelist, int psm, boolean suppressDawgs)
        throws TesseractException {
    Tesseract t = null;
    try {
        t = tesseractPool.poll(10, TimeUnit.SECONDS);
        if (t == null) {
            System.err.println("!!! POOL VACÍO — creando instancia temporal");
            t = createTesseractInstance();
        }

        t.setTessVariable("tessedit_char_whitelist", whitelist != null ? whitelist : "");
        String dawg = suppressDawgs ? "false" : "true";
        t.setTessVariable("load_system_dawg", dawg);
        t.setTessVariable("load_freq_dawg",   dawg);
        t.setPageSegMode(psm);

        System.out.println(">>> OCR iniciando PSM=" + psm + " whitelist=" + whitelist);
        String res = t.doOCR(img);
        System.out.println(">>> OCR terminó, chars=" + (res == null ? 0 : res.length()));
        return res == null ? "" : res.replaceAll("\\p{C}", " ").trim();

    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        System.err.println("!!! InterruptedException en ocrPass");
        return "";
    // *** AGREGA ESTE CATCH ***
    } catch (Exception e) {
        System.err.println("!!! EXCEPCIÓN en ocrPass: " + e.getClass().getName() + " — " + e.getMessage());
        e.printStackTrace();
        return "";
    } finally {
        if (t != null) tesseractPool.offer(t);
    }
}
    private int calculateNativeConfidence(BufferedImage img) {
        Tesseract t = tesseractPool.poll();
        if (t == null) return 0;
        try {
            List<net.sourceforge.tess4j.Word> words =
                t.getWords(img, ITessAPI.TessPageIteratorLevel.RIL_WORD);
            if (words == null || words.isEmpty()) return 0;
            int total = 0;
            for (net.sourceforge.tess4j.Word w : words) total += w.getConfidence();
            return total / words.size();
        } finally {
            tesseractPool.offer(t);
        }
    }

    // =======================================================================
    // TODO LO DE ABAJO ES IDÉNTICO A TU VERSIÓN ORIGINAL — sin tocar
    // =======================================================================

    private boolean looksLikeBack(String text) {
        if (text == null) return false;
        String upper = text.toUpperCase();
        return upper.contains("IDMEX") || upper.contains("<<");
    }

    private String extractNombreFrente(String rawText) {
        if (rawText == null || rawText.isBlank()) return null;
        String[] lines = rawText.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String upper = lines[i].toUpperCase().trim();
            if (!looksLikeNombreLabel(upper)) continue;
            String sinEtiqueta = upper.replaceAll("^.*?NOMBRE[:\\s]*", "").trim();
            sinEtiqueta = removeSexoTag(sinEtiqueta);
            if (!sinEtiqueta.isBlank()) {
                String fromInline = extractNombreDeBloque(sinEtiqueta);
                if (fromInline != null && !fromInline.isBlank()) return fromInline;
            }
            List<String> parts = new ArrayList<>();
            for (int j = i + 1; j < lines.length && j <= i + 5; j++) {
                String next = lines[j].toUpperCase().trim();
                if (next.isBlank()) continue;
                if (containsStopWord(next)) break;
                next = removeSexoTag(next);
                String cleaned = cleanNameFragment(next);
                if (cleaned.isBlank()) break;
                if (cleaned.length() < 2) continue;
                if (next.matches(".*\\d.*")) break;
                parts.add(cleaned);
            }
            if (!parts.isEmpty()) return String.join(" ", parts);
        }
        return extractNombreDeBloque(rawText.toUpperCase());
    }

    private String extractNombreDeBloque(String bloque) {
        if (bloque == null || bloque.isBlank()) return null;
        String upper = bloque.toUpperCase();
        int idx = upper.indexOf("NOMBRE");
        if (idx >= 0) upper = upper.substring(idx + 6).replaceAll("^[:\\s]*", "").trim();
        upper = removeSexoTag(upper);
        List<String> nameParts = new ArrayList<>();
        for (String token : upper.split("\\s+")) {
            if (token.isBlank()) continue;
            if (containsStopWord(token)) break;
            if (token.matches(".*\\d.*")) break;
            String clean = token.replaceAll("[^A-ZÁÉÍÓÚÑÜ]", "").trim();
            if (clean.length() <= 1) continue;
            nameParts.add(clean);
        }
        return nameParts.isEmpty() ? null : String.join(" ", nameParts);
    }

    private String removeSexoTag(String text) {
        if (text == null) return "";
        return text.replaceAll("(?i)SEXO\\s*[HM]?\\b", "").replaceAll("\\s+", " ").trim();
    }

    private boolean looksLikeNombreLabel(String upper) {
        return upper.equals("NOMBRE") || upper.startsWith("NOMBRE ")
            || upper.startsWith("NOMBRE:") || upper.matches("NOMBRE\\W.*");
    }

    private String cleanNameFragment(String s) {
        if (s == null) return "";
        return s.replaceAll("[^A-ZÁÉÍÓÚÑÜA-záéíóúñü\\s]", "")
                .replaceAll("\\s+", " ").trim().toUpperCase();
    }

    private boolean containsStopWord(String line) {
        if (line == null) return false;
        String upper = line.toUpperCase();
        for (String sw : STOP_WORDS) if (upper.contains(sw)) return true;
        return false;
    }

    private String rescueNombreFromFragments(String... texts) {
        for (String raw : texts) {
            if (raw == null || raw.isBlank()) continue;
            Matcher mb = Pattern.compile(
                "NOMBRE[:\\s]*(.*?)(?:DOMICILIO|CLAVE|CURP)",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(raw);
            if (mb.find()) {
                String result = extractNombreDeBloque(mb.group(1));
                if (result != null && result.length() >= 3) return result;
            }
        }
        return null;
    }

    private String extractNombreFromMrz(String rawText) {
        if (rawText == null) return null;
        String bestCandidate = null;
        int bestScore = 0;
        for (String line : rawText.split("\\r?\\n")) {
            String clean = line.toUpperCase().replaceAll("[^A-Z0-9<]", "").trim();
            if (clean.length() < 8 || !clean.contains("<<") || clean.startsWith("IDMEX")) continue;
            long digits  = clean.chars().filter(Character::isDigit).count();
            long letters = clean.chars().filter(Character::isLetter).count();
            if (digits > letters) continue;
            int score = (int)(letters - digits) + 10;
            if (score > bestScore) { bestScore = score; bestCandidate = clean; }
        }
        if (bestCandidate != null) return parseMrzNameLine(bestCandidate);
        String flat = rawText.toUpperCase().replaceAll("[^A-Z<]", "");
        Matcher mMrz = Pattern.compile("([A-Z]{2,})<([A-Z]{2,})<<([A-Z][A-Z<]{2,})").matcher(flat);
        if (mMrz.find()) {
            String noms = mMrz.group(3).replaceAll("<+$", "").replace("<", " ").trim();
            return (mMrz.group(1) + " " + mMrz.group(2) + " " + noms).trim();
        }
        return null;
    }

    private String parseMrzNameLine(String mrzLine) {
        String[] parts = mrzLine.replaceAll("<+$", "").split("<<", 2);
        String apellidos = parts[0].replace("<", " ").replaceAll("\\s+", " ").trim();
        String nombres   = parts.length > 1
            ? parts[1].replace("<", " ").replaceAll("[<\\s]+", " ").trim() : "";
        return (apellidos + " " + nombres).trim();
    }

    private String extractClaveElectorByLabel(String rawText) {
        if (rawText == null) return null;
        String[] lines = rawText.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String upper = lines[i].toUpperCase();
            Matcher labelMatcher = LABEL_PATTERN.matcher(upper);
            if (!labelMatcher.find()) continue;
            String afterLabel = upper.substring(labelMatcher.end()).trim();
            StringBuilder sb = new StringBuilder(afterLabel);
            for (int k = 1; k <= 2 && (i + k) < lines.length; k++) {
                sb.append(" ").append(lines[i + k].toUpperCase());
            }
            String fragment = sb.toString().replaceAll("[^A-Z0-9]", "");
            System.out.println("=== FRAGMENT CLAVE (tras etiqueta) ===\n" + fragment);
            String clave = extractClaveFromFragment(fragment);
            if (clave != null) return clave;
        }
        return null;
    }

    private String extractClaveFromFragment(String fragment) {
        if (fragment == null || fragment.isBlank()) return null;
        Pattern pStrict = Pattern.compile("[A-Z]{6}\\d{8}[A-Z]\\d{3}");
        Matcher m = pStrict.matcher(fragment);
        if (m.find()) {
            System.out.println("=== CLAVE (estricto) === " + m.group(0));
            return m.group(0);
        }
        String normalized = normalizeClaveOCR(fragment);
        System.out.println("=== FRAGMENT NORMALIZADO ===\n" + normalized);
        if (!normalized.equals(fragment)) {
            Matcher mn = pStrict.matcher(normalized);
            if (mn.find()) {
                System.out.println("=== CLAVE (normalizado) === " + mn.group(0));
                return mn.group(0);
            }
        }
        if (fragment.length() >= 16) {
            Pattern pRelax = Pattern.compile("[A-Z]{5,7}[A-Z0-9]{7,9}[A-Z][A-Z0-9]{2,4}");
            String src = normalized.isBlank() ? fragment : normalized;
            Matcher mRelax = pRelax.matcher(src);
            if (mRelax.find()) {
                String cand = mRelax.group(0);
                if (cand.length() >= 16 && cand.length() <= 20) {
                    System.out.println("=== CLAVE (relajado) === " + cand);
                    return cand;
                }
            }
        }
        System.out.println("=== CLAVE: no encontrada en fragmento ===");
        return null;
    }

    private String extractClaveElectorRegex(String text) {
        if (text == null) return null;
        String compact = text.toUpperCase().replaceAll("[^A-Z0-9]", "");
        return extractClaveFromFragment(compact);
    }

    private String normalizeClaveOCR(String fragment) {
        if (fragment == null || fragment.length() < 18) return fragment == null ? "" : fragment;
        String window = fragment.length() > 22 ? fragment.substring(0, 22) : fragment;
        Matcher mb = Pattern.compile("[A-Z0-9]{18}").matcher(window);
        while (mb.find()) {
            String raw18 = mb.group(0);
            char[] chars = raw18.toCharArray();
            System.out.println("=== CANDIDATE 18 (fragment) === " + raw18);
            int letrasInicio = 0;
            for (int i = 0; i <= 5; i++) if (Character.isLetter(chars[i])) letrasInicio++;
            if (letrasInicio < 4) { System.out.println("  → rechazado: letrasInicio=" + letrasInicio); continue; }
            int digitosMedio = 0;
            for (int i = 6; i <= 13; i++)
                if (Character.isDigit(chars[i]) || chars[i] == 'O') digitosMedio++;
            if (digitosMedio < 4) { System.out.println("  → rechazado: digitosMedio=" + digitosMedio); continue; }
            if (!Character.isLetter(chars[14]) && chars[14] != '0') { System.out.println("  → rechazado: pos14='" + chars[14] + "'"); continue; }
            int digitosFin = 0;
            for (int i = 15; i <= 17; i++)
                if (Character.isDigit(chars[i]) || chars[i] == 'O') digitosFin++;
            if (digitosFin < 2) { System.out.println("  → rechazado: digitosFin=" + digitosFin); continue; }
            for (int i = 0; i <= 5; i++)   if (chars[i] == '0') chars[i] = 'O';
            for (int i = 6; i <= 13; i++)  if (chars[i] == 'O') chars[i] = '0';
            if (chars[14] == '0') chars[14] = 'O';
            for (int i = 15; i <= 17; i++) if (chars[i] == 'O') chars[i] = '0';
            String candidate = new String(chars);
            System.out.println("  → normalizado: " + candidate);
            if (candidate.matches("[A-Z]{6}\\d{8}[A-Z]\\d{3}")) {
                return fragment.replaceFirst(Pattern.quote(raw18), candidate);
            }
        }
        return fragment;
    }

    private List<BufferedImage> preprocessVariants(BufferedImage src) {
        List<BufferedImage> variants = new ArrayList<>();
        variants.add(preprocessStandard(src));
        variants.add(preprocessHighContrast(src));
        variants.add(preprocessInverted(src));
        return variants;
    }

    private BufferedImage preprocessStandard(BufferedImage src) {
        return sharpen(ensureMinLongSide(deskewImage(toGray(src)), 1800));
    }

    private BufferedImage preprocessHighContrast(BufferedImage src) {
        BufferedImage scaled = ensureMinLongSide(deskewImage(toGray(src)), 1800);
        int[] lut = new int[256];
        for (int i = 0; i < 256; i++) {
            int v = (int)((i - 80) * (255.0 / (200 - 80)));
            lut[i] = Math.max(0, Math.min(255, v));
        }
        BufferedImage contrast = new BufferedImage(
            scaled.getWidth(), scaled.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < scaled.getHeight(); y++) {
            for (int x = 0; x < scaled.getWidth(); x++) {
                int mapped = lut[scaled.getRGB(x, y) & 0xFF];
                contrast.setRGB(x, y, 0xFF000000 | (mapped << 16) | (mapped << 8) | mapped);
            }
        }
        return sharpen(contrast);
    }

    private BufferedImage preprocessInverted(BufferedImage src) {
        BufferedImage scaled = ensureMinLongSide(toGray(src), 1800);
        BufferedImage inv    = new BufferedImage(
            scaled.getWidth(), scaled.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < scaled.getHeight(); y++) {
            for (int x = 0; x < scaled.getWidth(); x++) {
                int v = 255 - (scaled.getRGB(x, y) & 0xFF);
                inv.setRGB(x, y, 0xFF000000 | (v << 16) | (v << 8) | v);
            }
        }
        return sharpen(inv);
    }

    private BufferedImage toGray(BufferedImage src) {
        BufferedImage gray = new BufferedImage(
            src.getWidth(), src.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gray.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return gray;
    }

    private BufferedImage ensureMinLongSide(BufferedImage src, int min) {
        int maxDim = Math.max(src.getWidth(), src.getHeight());
        return maxDim < min ? scaleImage(src, (double) min / maxDim) : src;
    }

    private BufferedImage sharpen(BufferedImage src) {
        float[] k = {0f, -1f, 0f, -1f, 5f, -1f, 0f, -1f, 0f};
        return new ConvolveOp(new Kernel(3, 3, k), ConvolveOp.EDGE_NO_OP, null).filter(src, null);
    }

    private BufferedImage deskewImage(BufferedImage src) {
        try {
            double angle = new ImageDeskew(src).getSkewAngle();
            if (Math.abs(angle) > 0.05) return rotateImage(src, -angle);
        } catch (Exception ignored) {}
        return src;
    }

    private BufferedImage rotateImage(BufferedImage src, double angle) {
        double rad = Math.toRadians(angle);
        double sin = Math.abs(Math.sin(rad)), cos = Math.abs(Math.cos(rad));
        int w = (int) Math.floor(src.getWidth() * cos + src.getHeight() * sin);
        int h = (int) Math.floor(src.getWidth() * sin + src.getHeight() * cos);
        BufferedImage rotated = new BufferedImage(w, h, src.getType());
        AffineTransform at = new AffineTransform();
        at.translate(w / 2.0, h / 2.0);
        at.rotate(rad, 0, 0);
        at.translate(-src.getWidth() / 2.0, -src.getHeight() / 2.0);
        new AffineTransformOp(at, AffineTransformOp.TYPE_BILINEAR).filter(src, rotated);
        return rotated;
    }

    private BufferedImage scaleImage(BufferedImage src, double scale) {
        int w = (int) Math.round(src.getWidth()  * scale);
        int h = (int) Math.round(src.getHeight() * scale);
        BufferedImage dest = new BufferedImage(w, h, src.getType());
        new AffineTransformOp(
            AffineTransform.getScaleInstance(scale, scale),
            AffineTransformOp.TYPE_BILINEAR).filter(src, dest);
        return dest;
    }

    private String readQRCode(BufferedImage image) {
        try {
            BinaryBitmap bitmap = new BinaryBitmap(
                new HybridBinarizer(new BufferedImageLuminanceSource(image)));
            return new MultiFormatReader().decode(bitmap).getText();
        } catch (NotFoundException e) {
            return null;
        } catch (Exception e) {
            System.err.println("Error leyendo QR: " + e.getMessage());
            return null;
        }
    }
}