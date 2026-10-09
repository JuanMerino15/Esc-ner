package com.juan.siam.springboot.scanner.scanner.controllers;

import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validación cruzada con los datos REDUNDANTES que la propia INE imprime en el frente:
 *
 *   FECHA DE NACIMIENTO  dd/mm/aaaa  ->  debe ser igual al AAMMDD de la clave de elector y de la CURP
 *   SEXO H/M             ->  debe ser igual al carácter 15 de la clave y al 11 de la CURP
 *   CURP (estado "TL")   ->  debe corresponder a la entidad (dígitos 13-14) de la clave ("29")
 *   NOMBRE               ->  de él salen las 6 letras de la clave (RIVERA MORALES ERICK -> RV MR ER)
 *
 * Con eso la clave deja de decidirse sólo por "qué leyó más veces Tesseract" (errores correlacionados
 * entre pasadas, p. ej. 9 -> 1) y pasa a decidirse por evidencia independiente.
 *
 * Sin dependencias externas (sólo JDK) para poder probarse aislada.
 */
final class IneCrossCheck {
    private IneCrossCheck() {}

    /** Edad mínima / máxima razonable de un titular de credencial para votar. */
    static final int MIN_AGE = 17;
    static final int MAX_AGE = 105;

    /** Clave de entidad INE (01..33) -> letras de entidad que usa la CURP. */
    private static final String[] STATE_BY_CODE = {
        "", "AS", "BC", "BS", "CC", "CL", "CM", "CS", "CH", "DF", "DG", "GT", "GR", "HG", "JC", "MC", "MN",
        "MS", "NT", "NL", "OC", "PL", "QT", "QR", "SP", "SL", "SR", "TC", "TS", "TL", "VZ", "YN", "ZS", "NE"};
    private static final Set<String> CURP_STATES =
        new HashSet<>(Arrays.asList(STATE_BY_CODE).subList(1, STATE_BY_CODE.length));

    private static final Set<String> PARTICLES =
        new HashSet<>(Arrays.asList("DE", "DEL", "LA", "LAS", "LOS", "MC", "VAN", "VON", "Y"));
    private static final Set<String> SKIP_GIVEN = new HashSet<>(Arrays.asList("MARIA", "MA", "JOSE", "J"));

    private static final Pattern CLAVE_SHAPE = Pattern.compile("[A-Z]{6}\\d{8}[HM]\\d{3}");
    /** dd/mm/aaaa tolerando O/I/L/S/B/Z leídos en lugar de dígitos. */
    private static final Pattern DATE_P = Pattern.compile(
        "([0-9OILSBZ|]{2})\\s?[/\\-.]\\s?([0-9OILSBZ|]{2})\\s?[/\\-.]\\s?([0-9OILSBZ|]{4})");
    private static final Pattern SEXO_P = Pattern.compile("SEX[O0]\\W{0,3}([HM])(?![A-Z])");
    private static final int[] DIM = {31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};

    // ------------------------------------------------------------------ utilidades
    static String norm(String s) {
        if (s == null) return "";
        String t = s.toUpperCase(Locale.ROOT).replace('\u00D1', '\u0001');
        t = Normalizer.normalize(t, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return t.replace('\u0001', '\u00D1');
    }

    static boolean validDate(int y, int m, int d) {
        try {
            LocalDate.of(y, m, d);
            return true;
        } catch (DateTimeException e) {
            return false;
        }
    }

    static int resolveYear(int yy, int mm, int dd) {
        return resolveYear(yy, mm, dd, LocalDate.now());
    }

    /**
     * Año completo (siglo XX o XXI) para el que AAMMDD da una edad plausible, o -1 si ninguno.
     * "150702" -> 2015 (11 años) o 1915 (111 años): imposible para una INE -> -1.
     */
    static int resolveYear(int yy, int mm, int dd, LocalDate today) {
        for (int century : new int[] {2000, 1900}) {
            int y = century + yy;
            if (!validDate(y, mm, dd)) continue;
            int age = Period.between(LocalDate.of(y, mm, dd), today).getYears();
            if (age >= MIN_AGE && age <= MAX_AGE) return y;
        }
        return -1;
    }

    /** Forma + mes/día + entidad. (No mira el año: eso lo hace resolveYear.) */
    static boolean shapeOk(String c) {
        if (c == null || !CLAVE_SHAPE.matcher(c).matches()) return false;
        int mm = Integer.parseInt(c.substring(8, 10));
        int dd = Integer.parseInt(c.substring(10, 12));
        int st = Integer.parseInt(c.substring(12, 14));
        return mm >= 1 && mm <= 12 && st >= 1 && st <= 33 && dd >= 1 && dd <= DIM[mm - 1];
    }

    /** Forma + fecha + entidad + EDAD PLAUSIBLE. Úsalo en IneParser.isValidClave. */
    static boolean claveOk(String c) {
        if (!shapeOk(c)) return false;
        return resolveYear(Integer.parseInt(c.substring(6, 8)),
            Integer.parseInt(c.substring(8, 10)), Integer.parseInt(c.substring(10, 12))) > 0;
    }

    private static int digitOf(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        switch (c) {
            case 'O': return 0;
            case 'I': case 'L': case '|': return 1;
            case 'Z': return 2;
            case 'S': return 5;
            case 'B': return 8;
            default: return -1;
        }
    }

    private static int toInt(String s) {
        int v = 0;
        for (int i = 0; i < s.length(); i++) {
            int d = digitOf(s.charAt(i));
            if (d < 0) return -1;
            v = v * 10 + d;
        }
        return v;
    }

    // ------------------------------------------------------------------ fecha impresa
    static final class Fecha {
        final int y, m, d;
        Fecha(int y, int m, int d) { this.y = y; this.m = m; this.d = d; }
        String key() { return String.format("%04d-%02d-%02d", y, m, d); }
        String yymmdd() { return String.format("%02d%02d%02d", y % 100, m, d); }
        String display() { return String.format("%02d/%02d/%04d", d, m, y); }
    }

    private static boolean nearNacimiento(String[] lines, int i) {
        for (int k = Math.max(0, i - 2); k <= i; k++) {
            if (lines[k].contains("NACIM") || lines[k].contains("FECHA")) return true;
        }
        return false;
    }

    /** Fecha de nacimiento impresa (dd/mm/aaaa). Prefiere la que está bajo la etiqueta. */
    static Fecha extractFecha(String text) {
        if (text == null) return null;
        String[] lines = norm(text).split("\\r?\\n");
        LocalDate today = LocalDate.now();
        Fecha fallback = null;
        for (int i = 0; i < lines.length; i++) {
            Matcher m = DATE_P.matcher(lines[i]);
            while (m.find()) {
                int d = toInt(m.group(1)), mo = toInt(m.group(2)), y = toInt(m.group(3));
                if (d < 0 || mo < 0 || y < 0 || !validDate(y, mo, d)) continue;
                int age = Period.between(LocalDate.of(y, mo, d), today).getYears();
                if (age < MIN_AGE || age > MAX_AGE) continue;
                Fecha f = new Fecha(y, mo, d);
                if (nearNacimiento(lines, i)) return f;
                if (fallback == null) fallback = f;
            }
        }
        return fallback;
    }

    static Character extractSexo(String text) {
        Matcher m = SEXO_P.matcher(norm(text));
        return m.find() ? m.group(1).charAt(0) : null;
    }

    // ------------------------------------------------------------------ CURP
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
     * CURP: 4 letras + AAMMDD + H/M + entidad(2 letras) + 3 consonantes + 1 alfanumérico + 1 dígito.
     * Ventana de 18 con corrección por tipo de posición (igual que la clave). Se usa SÓLO como
     * evidencia secundaria, por eso se exige costo <= 3 y entidad/sexo/fecha coherentes.
     */
    static String extractCurp(String text) {
        if (text == null) return null;
        String best = null;
        int bestCost = 99;
        for (String line : norm(text).split("\\r?\\n")) {
            String s = line.replaceAll("[^A-Z0-9]", "");
            for (int i = 0; i + 18 <= s.length(); i++) {
                char[] out = new char[18];
                int cost = 0;
                boolean ok = true;
                for (int k = 0; k < 18 && ok; k++) {
                    char c = s.charAt(i + k);
                    char r;
                    if (k <= 3 || (k >= 10 && k <= 15)) r = toLetter(c);
                    else if (k == 16) r = (Character.isLetterOrDigit(c) ? c : 0);
                    else r = toDigit(c);
                    if (r == 0) ok = false;
                    else { if (r != c) cost++; out[k] = r; }
                }
                if (!ok || cost > 3) continue;
                if (out[10] != 'H' && out[10] != 'M') continue;
                String cand = new String(out);
                if (!CURP_STATES.contains(cand.substring(11, 13))) continue;
                int mm = Integer.parseInt(cand.substring(6, 8));
                int dd = Integer.parseInt(cand.substring(8, 10));
                if (mm < 1 || mm > 12 || dd < 1 || dd > DIM[mm - 1]) continue;
                if (cost < bestCost) { bestCost = cost; best = cand; }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ letras desde el nombre
    private static String takeGroup(List<String> t) {
        String sig = "";
        while (!t.isEmpty()) {
            String w = t.remove(0);
            if (PARTICLES.contains(w) && !t.isEmpty()) continue;   // DE / DE LA / DEL ...
            sig = w;
            break;
        }
        return sig;
    }

    /** Variantes {paterno, materno, nombre} (palabra significativa de cada parte). */
    static List<String[]> nameVariants(String full) {
        List<String[]> out = new ArrayList<>();
        if (full == null) return out;
        List<String> t = new ArrayList<>();
        for (String s : norm(full).split("[^A-Z\u00D1]+")) if (!s.isEmpty()) t.add(s);
        if (t.size() < 2) return out;
        for (int conMaterno = 1; conMaterno >= 0; conMaterno--) {
            List<String> rest = new ArrayList<>(t);
            String p = takeGroup(rest);
            String m = "";
            if (conMaterno == 1) {
                if (rest.size() < 2) continue;
                m = takeGroup(rest);
            }
            if (rest.isEmpty()) continue;
            out.add(new String[] {p, m, rest.get(0)});
            if (rest.size() > 1 && SKIP_GIVEN.contains(rest.get(0))) out.add(new String[] {p, m, rest.get(1)});
        }
        return out;
    }

    private static char x(char c) { return c == '\u00D1' ? 'X' : c; }

    /** Primera letra + primera consonante interna ("RIVERA" -> "RV", "MORALES" -> "MR", "ERICK" -> "ER"). */
    private static String pair(String w) {
        if (w == null || w.isEmpty()) return "XX";
        char first = x(w.charAt(0));
        char second = 'X';
        for (int i = 1; i < w.length(); i++) {
            char c = w.charAt(i);
            if (Character.isLetter(c) && "AEIOU".indexOf(c) < 0) { second = x(c); break; }
        }
        return "" + first + second;
    }

    /** Mejor coincidencia (0..6) entre las 6 letras de la clave y las derivadas del nombre; -1 si no hay nombre. */
    static int letterMatches(String letters6, List<String[]> variants) {
        int best = -1;
        for (String[] v : variants) {
            String exp = pair(v[0]) + pair(v[1]) + pair(v[2]);
            int n = 0;
            for (int i = 0; i < 6; i++) if (exp.charAt(i) == letters6.charAt(i)) n++;
            best = Math.max(best, n);
        }
        return best;
    }

    /** Letras esperadas (6) de la variante del nombre que más se parece a las de la clave; null si no hay nombre. */
    private static String expectedLetters(String letters6, List<String[]> variants) {
        String bestExp = null;
        int best = -1;
        for (String[] v : variants) {
            String exp = pair(v[0]) + pair(v[1]) + pair(v[2]);
            int n = 0;
            for (int i = 0; i < 6; i++) if (exp.charAt(i) == letters6.charAt(i)) n++;
            if (n > best) { best = n; bestExp = exp; }
        }
        return bestExp;
    }

    /**
     * La CURP repite las letras de la clave de elector:
     *   clave[0]=CURP[0] (inicial paterno)   clave[1]=CURP[13] (1a consonante interna del paterno)
     *   clave[2]=CURP[2] (inicial materno)   clave[3]=CURP[14] (1a consonante interna del materno)
     *   clave[4]=CURP[3] (inicial nombre)    clave[5]=CURP[15] (1a consonante interna del nombre)
     */
    private static String curpAsClaveLetters(String curp) {
        return "" + curp.charAt(0) + curp.charAt(13) + curp.charAt(2)
            + curp.charAt(14) + curp.charAt(3) + curp.charAt(15);
    }

    private static String stateLetters(String code2) {
        int n = toInt(code2);
        return (n >= 1 && n <= 33) ? STATE_BY_CODE[n] : null;
    }

    // ------------------------------------------------------------------ evidencia por pasada
    private static final class Tally {
        double w;
        int n;
        Fecha f;
    }

    private static <K> Tally vote(Map<K, Tally> m, K key, double w) {
        Tally t = m.get(key);
        if (t == null) { t = new Tally(); m.put(key, t); }
        t.w += w;
        t.n++;
        return t;
    }

    private static <K> Tally top(Map<K, Tally> m, K[] keyOut) {
        Tally best = null;
        for (Map.Entry<K, Tally> e : m.entrySet()) {
            if (best == null || e.getValue().w > best.w) { best = e.getValue(); keyOut[0] = e.getKey(); }
        }
        return best;
    }

    /** Acumula lo que cada pasada de OCR alcanzó a leer de la fecha, la CURP y el sexo. */
    static final class Evidence {
        private final Map<String, Tally> fechas = new LinkedHashMap<>();
        private final Map<String, Tally> curps = new LinkedHashMap<>();
        private final Map<Character, Tally> sexos = new LinkedHashMap<>();

        void add(String passText, double weight) {
            if (passText == null || passText.isEmpty()) return;
            Fecha f = extractFecha(passText);
            if (f != null) vote(fechas, f.key(), weight).f = f;
            String curp = extractCurp(passText);
            if (curp != null) vote(curps, curp, weight);
            Character s = extractSexo(passText);
            if (s != null) vote(sexos, s, weight);
        }

        Fecha bestFecha() {
            String[] k = new String[1];
            Tally t = top(fechas, k);
            return t == null ? null : t.f;
        }

        int bestFechaCount() {
            String[] k = new String[1];
            Tally t = top(fechas, k);
            return t == null ? 0 : t.n;
        }

        String bestCurp() {
            String[] k = new String[1];
            return top(curps, k) == null ? null : k[0];
        }

        Character bestSexo() {
            Character[] k = new Character[1];
            return top(sexos, k) == null ? null : k[0];
        }
    }

    // ------------------------------------------------------------------ arbitraje de la clave
    static final class Result {
        String clave;
        boolean corregida;          // se reparó el AAMMDD con la fecha impresa
        boolean letrasCorregidas;   // se repararon letras de la clave con el nombre / la CURP
        boolean reparacionDebil;    // la reparación de letras se apoyó en UNA sola fuente (el nombre)
        Boolean fechaOk;            // null = no se pudo leer la fecha impresa
        Boolean letrasOk;           // null = no hay nombre
        int confirmaciones;         // fuentes independientes que respaldan la clave final
        String fechaImpresa;        // dd/mm/aaaa
        String curp;
        final List<String> notas = new ArrayList<>();

        boolean conflicto() {
            return Boolean.FALSE.equals(fechaOk) || Boolean.FALSE.equals(letrasOk) || reparacionDebil;
        }
    }

    private static double bonus(String c, Fecha fecha, String curp, Character sexo, List<String[]> nv) {
        if (!CLAVE_SHAPE.matcher(c).matches()) return -30;
        double b = 0;
        if (!shapeOk(c)) b -= 20;
        String ymd = c.substring(6, 12);
        if (resolveYear(Integer.parseInt(c.substring(6, 8)), Integer.parseInt(c.substring(8, 10)),
            Integer.parseInt(c.substring(10, 12))) < 0) b -= 10;                 // edad imposible
        if (fecha != null && ymd.equals(fecha.yymmdd())) b += 6;               // fecha impresa
        if (curp != null) {
            if (ymd.equals(curp.substring(4, 10))) b += 1.5;
            String st = stateLetters(c.substring(12, 14));
            if (st != null && st.equals(curp.substring(11, 13))) b += 1.5;
            if (c.charAt(14) == curp.charAt(10)) b += 1;
        }
        if (sexo != null && c.charAt(14) == sexo) b += 2;
        int lm = letterMatches(c.substring(0, 6), nv);
        if (lm >= 0) b += lm;                                   // hasta +6 por letras del nombre
        return b;
    }

    /**
     * @param cands  clave -> peso acumulado de las pasadas (incluye el candidato por votación carácter a carácter)
     * @param ev     evidencia acumulada (fecha impresa, CURP, sexo)
     * @param nombre nombre ya elegido (puede ser null)
     */
    static Result resolveClave(Map<String, Double> cands, Evidence ev, String nombre) {
        return resolveClave(cands, ev, nombre, 0);
    }

    /** @param nombreSupport cuántas pasadas respaldan el nombre (>=2 permite reparar letras sólo con el nombre) */
    static Result resolveClave(Map<String, Double> cands, Evidence ev, String nombre, int nombreSupport) {
        Result r = new Result();
        if (cands == null || cands.isEmpty()) return r;

        Fecha fecha = ev.bestFecha();
        int fechaN = ev.bestFechaCount();
        String curp = ev.bestCurp();
        Character sexo = ev.bestSexo();
        List<String[]> nv = nameVariants(nombre);
        r.curp = curp;

        String best = null;
        double bestScore = -1e9;
        for (Map.Entry<String, Double> en : cands.entrySet()) {
            double s = en.getValue() + bonus(en.getKey(), fecha, curp, sexo, nv);
            if (s > bestScore) { bestScore = s; best = en.getKey(); }
        }

        String chosen = best;
        boolean shape = CLAVE_SHAPE.matcher(chosen).matches();

        // Reparación: la fecha impresa manda sobre el AAMMDD de la clave cuando hay respaldo suficiente.
        if (fecha != null && shape && !chosen.substring(6, 12).equals(fecha.yymmdd())) {
            boolean imposible = resolveYear(Integer.parseInt(chosen.substring(6, 8)),
                Integer.parseInt(chosen.substring(8, 10)), Integer.parseInt(chosen.substring(10, 12))) < 0;
            if (fechaN >= 2 || imposible) {
                String repaired = chosen.substring(0, 6) + fecha.yymmdd() + chosen.substring(12);
                if (shapeOk(repaired)) {
                    r.notas.add("AAMMDD " + chosen.substring(6, 12) + " -> " + fecha.yymmdd()
                        + " (fecha impresa " + fecha.display() + ", leída en " + fechaN + " pasada/s)");
                    chosen = repaired;
                    r.corregida = true;
                }
            }
        }
        // Reparación de letras: el nombre (leído con modelo de lenguaje) y la CURP respaldan las 6 letras.
        if (shape && !nv.isEmpty()) {
            String exp = expectedLetters(chosen.substring(0, 6), nv);
            String cl = curp != null ? curpAsClaveLetters(curp) : null;
            char[] cur = chosen.substring(0, 6).toCharArray();
            int mism = 0;
            for (int i = 0; i < 6; i++) if (exp.charAt(i) != cur[i]) mism++;
            if (mism >= 1 && mism <= 2) {
                boolean changed = false, weak = false;
                for (int i = 0; i < 6; i++) {
                    char e = exp.charAt(i);
                    if (e == cur[i]) continue;
                    char c = cl == null ? 0 : cl.charAt(i);
                    if (c == e) {                                   // nombre + CURP coinciden
                        r.notas.add("Letra " + (i + 1) + " de la clave " + cur[i] + " -> " + e + " (nombre y CURP)");
                        cur[i] = e;
                        changed = true;
                    } else if (c != cur[i] && mism == 1 && nombreSupport >= 2) {   // sólo el nombre; la CURP no se opone
                        r.notas.add("Letra " + (i + 1) + " de la clave " + cur[i] + " -> " + e + " (sólo nombre)");
                        cur[i] = e;
                        changed = true;
                        weak = true;
                    }
                }
                if (changed) {
                    String repaired = new String(cur) + chosen.substring(6);
                    if (shapeOk(repaired)) {
                        chosen = repaired;
                        r.letrasCorregidas = true;
                        r.reparacionDebil = weak;
                    }
                }
            }
        }
        r.clave = chosen;
        shape = CLAVE_SHAPE.matcher(chosen).matches();
        if (!shape) return r;

        if (fecha != null) {
            r.fechaImpresa = fecha.display();
            r.fechaOk = chosen.substring(6, 12).equals(fecha.yymmdd());
            if (r.fechaOk) r.confirmaciones++;
            else r.notas.add("La fecha impresa " + fecha.display() + " no coincide con la clave");
        }
        if (curp != null) {
            if (chosen.substring(6, 12).equals(curp.substring(4, 10))) r.confirmaciones++;
            String st = stateLetters(chosen.substring(12, 14));
            if (st != null && st.equals(curp.substring(11, 13))) r.confirmaciones++;
        }
        if (sexo != null && chosen.charAt(14) == sexo) r.confirmaciones++;
        int lm = letterMatches(chosen.substring(0, 6), nv);
        if (lm >= 0) {
            r.letrasOk = (lm == 6);   // estricto: 5/6 también se marca para revisión
            if (lm == 6) r.confirmaciones++;
            else r.notas.add("Las letras de la clave coinciden " + lm + "/6 con el nombre");
        }
        return r;
    }
}