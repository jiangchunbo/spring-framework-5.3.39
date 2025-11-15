/*
 * Copyright 2002-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.core.env;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.function.Predicate;

import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Internal parser used by {@link Profiles#of}.
 *
 * @author Phillip Webb
 * @author Sam Brannen
 * @since 5.1
 */
final class ProfilesParser {

	private ProfilesParser() {
	}

	/**
	 * @param expressions 可变表达式，甚至 dev 就是一个表达式
	 */
	static Profiles parse(String... expressions) {
		Assert.notEmpty(expressions, "Must specify at least one profile expression");
		Profiles[] parsed = new Profiles[expressions.length];
		for (int i = 0; i < expressions.length; i++) {
			// 解析表达式，就是看表达式是否存在 ()&|!
			parsed[i] = parseExpression(expressions[i]);
		}
		return new ParsedProfiles(expressions, parsed);
	}

	private static Profiles parseExpression(String expression) {
		Assert.hasText(expression, () -> "Invalid profile expression [" + expression + "]: must contain text");
		StringTokenizer tokens = new StringTokenizer(expression, "()&|!", true);
		return parseTokens(expression, tokens);
	}

	private static Profiles parseTokens(String expression, StringTokenizer tokens) {
		return parseTokens(expression, tokens, Context.NONE);
	}

	private static Profiles parseTokens(String expression, StringTokenizer tokens, Context context) {
		List<Profiles> elements = new ArrayList<>();
		Operator operator = null;
		while (tokens.hasMoreTokens()) {
			String token = tokens.nextToken().trim();
			if (token.isEmpty()) {
				continue;
			}
			switch (token) {
				case "(":
					// 如果发现 ( ，那么标记进入 PARENTHESIS 括号上下文中
					Profiles contents = parseTokens(expression, tokens, Context.PARENTHESIS);
					if (context == Context.NEGATE) {
						return contents;
					}
					elements.add(contents);
					break;
				case "&":
					assertWellFormed(expression, operator == null || operator == Operator.AND);
					operator = Operator.AND;
					break;
				case "|":
					assertWellFormed(expression, operator == null || operator == Operator.OR);
					operator = Operator.OR;
					break;
				case "!":
					elements.add(not(parseTokens(expression, tokens, Context.NEGATE)));
					break;
				case ")":
					Profiles merged = merge(expression, elements, operator);
					if (context == Context.PARENTHESIS) {
						return merged;
					}
					elements.clear();
					elements.add(merged);
					operator = null;
					break;
				default:
					Profiles value = equals(token);
					if (context == Context.NEGATE) {
						return value;
					}
					elements.add(value);
			}
		}
		return merge(expression, elements, operator);
	}

	private static Profiles merge(String expression, List<Profiles> elements, @Nullable Operator operator) {
		assertWellFormed(expression, !elements.isEmpty());
		if (elements.size() == 1) {
			return elements.get(0);
		}
		Profiles[] profiles = elements.toArray(new Profiles[0]);
		return (operator == Operator.AND ? and(profiles) : or(profiles));
	}

	private static void assertWellFormed(String expression, boolean wellFormed) {
		Assert.isTrue(wellFormed, () -> "Malformed profile expression [" + expression + "]");
	}

	private static Profiles or(Profiles... profiles) {
		return activeProfile -> Arrays.stream(profiles).anyMatch(isMatch(activeProfile));
	}

	private static Profiles and(Profiles... profiles) {
		return activeProfile -> Arrays.stream(profiles).allMatch(isMatch(activeProfile));
	}

	private static Profiles not(Profiles profiles) {
		return activeProfile -> !profiles.matches(activeProfile);
	}

	private static Profiles equals(String profile) {
		return activeProfile -> activeProfile.test(profile);
	}

	private static Predicate<Profiles> isMatch(Predicate<String> activeProfiles) {
		return profiles -> profiles.matches(activeProfiles);
	}

	private enum Operator {AND, OR}

	/**
	 * NEGATE 		否定
	 * PARENTHESIS  括号
	 */
	private enum Context {NONE, NEGATE, PARENTHESIS}

	/**
	 * Profiles 的组合体
	 */
	private static class ParsedProfiles implements Profiles {

		private final Set<String> expressions = new LinkedHashSet<>();

		/**
		 * 组合了所有的 Profiles
		 */
		private final Profiles[] parsed;

		ParsedProfiles(String[] expressions, Profiles[] parsed) {
			Collections.addAll(this.expressions, expressions);
			this.parsed = parsed;
		}

		/**
		 * 匹配是否有任何一个命中
		 */
		@Override
		public boolean matches(Predicate<String> activeProfiles) {

			for (Profiles candidate : this.parsed) {
				// 如果匹配，则返回 true
				if (candidate.matches(activeProfiles)) {
					return true;
				}
			}
			return false;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null || getClass() != obj.getClass()) {
				return false;
			}
			ParsedProfiles that = (ParsedProfiles) obj;
			return this.expressions.equals(that.expressions);
		}

		@Override
		public int hashCode() {
			return this.expressions.hashCode();
		}

		@Override
		public String toString() {
			return StringUtils.collectionToDelimitedString(this.expressions, " or ");
		}

	}

}
