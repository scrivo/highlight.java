/*
 * Canonical dump of the Java language definitions, in the same format as dump.js,
 * so both can be compared to verify the transpiled classes.
 *
 * usage: java -cp target/classes tools/transpiler/Dump.java <output dir> [language...]
 */

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.scrivo.highlight.Language;
import org.scrivo.highlight.Mode;
import org.scrivo.highlight.languages.Registry;

public class Dump {

	private final Map<Object, Integer> ids = new IdentityHashMap<>();
	private int next = 1;

	@SuppressWarnings("unchecked")
	public static void main(String[] args) throws IOException {
		Path outDir = Path.of(args[0]);
		List<String> names = args.length > 1 ? List.of(args).subList(1, args.length)
				: new ArrayList<>(Registry.LANGUAGES.keySet());

		Map<String, Language> languages = new HashMap<>();
		java.util.function.Function<String, Language>[] resolver = new java.util.function.Function[1];
		resolver[0] = name -> {
			if (!Registry.LANGUAGES.containsKey(name)) {
				return null;
			}
			return languages.computeIfAbsent(name,
					n -> new Language(n, Registry.LANGUAGES.get(n).get(), resolver[0]));
		};

		Files.createDirectories(outDir);
		for (String name : names) {
			Language language = new Language(name, Registry.LANGUAGES.get(name).get(), resolver[0]);
			StringBuilder sb = new StringBuilder();
			json(new Dump().walk(language.rawDefinition(), false), sb);
			Files.writeString(outDir.resolve(name + ".json"), sb.append('\n'), StandardCharsets.UTF_8);
		}
	}

	private Object walk(Object value, boolean inKeywords) {
		if (value == null) {
			return null;
		}
		if (value == Mode.SELF) {
			return "self";
		}
		if (value instanceof Mode.Callback) {
			return "<function>";
		}
		if (value instanceof List<?> list) {
			List<Object> out = new ArrayList<>();
			for (Object v : list) {
				out.add(walk(v, inKeywords));
			}
			return out;
		}
		if (value instanceof Mode || value instanceof Map) {
			if (!inKeywords && ids.containsKey(value)) {
				return Map.of("$ref", ids.get(value));
			}
			Map<String, Object> out = new TreeMap<>();
			if (!inKeywords) {
				ids.put(value, next);
				out.put("$id", next++);
			}
			Map<String, Object> props = new TreeMap<>();
			if (value instanceof Mode mode) {
				for (String key : mode.keys()) {
					props.put(key, mode.get(key));
				}
			} else {
				for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
					props.put((String) e.getKey(), e.getValue());
				}
			}
			for (Map.Entry<String, Object> e : props.entrySet()) {
				if (e.getValue() != null) {
					out.put(e.getKey(), walk(e.getValue(), inKeywords || e.getKey().equals("keywords")));
				}
			}
			return out;
		}
		return value;
	}

	private static void json(Object value, StringBuilder sb) {
		if (value == null) {
			sb.append("null");
		} else if (value instanceof String s) {
			sb.append('"');
			for (int i = 0; i < s.length(); i++) {
				char ch = s.charAt(i);
				switch (ch) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> {
					if (ch < 0x20) {
						sb.append(String.format("\\u%04x", (int) ch));
					} else {
						sb.append(ch);
					}
				}
				}
			}
			sb.append('"');
		} else if (value instanceof List<?> list) {
			sb.append('[');
			for (int i = 0; i < list.size(); i++) {
				if (i > 0) {
					sb.append(',');
				}
				json(list.get(i), sb);
			}
			sb.append(']');
		} else if (value instanceof Map<?, ?> map) {
			sb.append('{');
			boolean first = true;
			for (Map.Entry<?, ?> e : map.entrySet()) {
				if (!first) {
					sb.append(',');
				}
				first = false;
				json(e.getKey(), sb);
				sb.append(':');
				json(e.getValue(), sb);
			}
			sb.append('}');
		} else {
			sb.append(value);
		}
	}
}
