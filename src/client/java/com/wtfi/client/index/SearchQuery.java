package com.wtfi.client.index;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parsed search box text. Terms are ANDed together:
 * <ul>
 *   <li>{@code word} – name or id contains word</li>
 *   <li>{@code @mod} – mod id or mod name contains mod</li>
 *   <li>{@code #tag} – one of the item's tags contains tag</li>
 *   <li>{@code $text} – tooltip contains text</li>
 *   <li>{@code -term} – negates any of the above</li>
 * </ul>
 */
public final class SearchQuery {
	public static final SearchQuery EMPTY = new SearchQuery("", List.of());

	public final String raw;
	private final List<Term> terms;

	private SearchQuery(String raw, List<Term> terms) {
		this.raw = raw;
		this.terms = terms;
	}

	public static SearchQuery parse(String text) {
		String raw = text.trim().toLowerCase(Locale.ROOT);
		if (raw.isEmpty()) return EMPTY;
		List<Term> terms = new ArrayList<>();
		for (String token : raw.split("\\s+")) {
			boolean negate = false;
			if (token.startsWith("-") && token.length() > 1) {
				negate = true;
				token = token.substring(1);
			}
			Kind kind = switch (token.charAt(0)) {
				case '@' -> Kind.MOD;
				case '#' -> Kind.TAG;
				case '$' -> Kind.TOOLTIP;
				default -> Kind.NAME;
			};
			String value = kind == Kind.NAME ? token : token.substring(1);
			if (value.isEmpty()) continue;
			terms.add(new Term(kind, value, negate));
		}
		return new SearchQuery(raw, List.copyOf(terms));
	}

	public boolean isEmpty() {
		return this.terms.isEmpty();
	}

	public boolean usesTags() {
		return this.terms.stream().anyMatch(t -> t.kind == Kind.TAG);
	}

	public boolean usesTooltips() {
		return this.terms.stream().anyMatch(t -> t.kind == Kind.TOOLTIP);
	}

	/** True when every result of this query is also a result of {@code previous}. */
	public boolean narrows(SearchQuery previous) {
		if (previous.terms.isEmpty()) return true;
		if (this.terms.size() < previous.terms.size()) return false;
		for (int i = 0; i < previous.terms.size(); i++) {
			Term old = previous.terms.get(i);
			Term now = this.terms.get(i);
			if (old.kind != now.kind || old.negate || now.negate) {
				if (!old.equals(now)) return false;
				continue;
			}
			if (!now.value.contains(old.value)) return false;
		}
		return true;
	}

	public boolean matches(IndexEntry entry) {
		for (Term term : this.terms) {
			if (term.test(entry) == term.negate) return false;
		}
		return true;
	}

	private enum Kind {
		NAME,
		MOD,
		TAG,
		TOOLTIP
	}

	private record Term(Kind kind, String value, boolean negate) {
		boolean test(IndexEntry entry) {
			return switch (this.kind) {
				case NAME -> entry.nameLower.contains(this.value) || entry.idLower.contains(this.value);
				case MOD -> entry.modLower.contains(this.value);
				case TAG -> {
					String tags = entry.tagsLower;
					yield tags != null && tags.contains(this.value);
				}
				case TOOLTIP -> {
					String tooltip = entry.tooltipLower;
					yield tooltip != null ? tooltip.contains(this.value) : entry.nameLower.contains(this.value);
				}
			};
		}
	}
}
