/*
 * Copyright 2002-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.web.reactive.result.view;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.beans.BeanUtils;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.ReactiveAdapter;
import org.springframework.core.ReactiveAdapterRegistry;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.http.MediaType;
import org.springframework.ui.Model;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.support.WebExchangeDataBinder;
import org.springframework.web.reactive.BindingContext;
import org.springframework.web.reactive.HandlerResult;
import org.springframework.web.reactive.HandlerResultHandler;
import org.springframework.web.reactive.accept.RequestedContentTypeResolver;
import org.springframework.web.reactive.result.HandlerResultHandlerSupport;
import org.springframework.web.server.NotAcceptableStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.support.HttpRequestPathHelper;

/**
 * {@code HandlerResultHandler} that encapsulates the view resolution algorithm
 * supporting the following return types:
 * <ul>
 *     <li>String-based view name
 *     <li>Reference to a {@link View}
 *     <li>{@link Model}
 *     <li>{@link Map}
 *     <li>Return types annotated with {@code @ModelAttribute}
 *     <li>{@link BeanUtils#isSimpleProperty Non-simple} return types are
 *     treated as a model attribute
 * </ul>
 *
 * <p>A String-based view name is resolved through the configured
 * {@link ViewResolver} instances into a {@link View} to use for rendering.
 * If a view is left unspecified (e.g. by returning {@code null} or a
 * model-related return value), a default view name is selected.
 *
 * <p>By default this resolver is ordered at {@link Ordered#LOWEST_PRECEDENCE}
 * and generally needs to be late in the order since it interprets any String
 * return value as a view name while others may interpret the same otherwise
 * based on annotations (e.g. for {@code @ResponseBody}).
 *
 * @author Rossen Stoyanchev
 * @since 5.0
 */
public class ViewResolutionResultHandler extends HandlerResultHandlerSupport
		implements HandlerResultHandler, Ordered {

	private static final Object NO_VALUE = new Object();

	private static final Mono<Object> NO_VALUE_MONO = Mono.just(NO_VALUE);


	private final List<ViewResolver> viewResolvers = new ArrayList<>(4);

	private final List<View> defaultViews = new ArrayList<>(4);

	private final HttpRequestPathHelper pathHelper = new HttpRequestPathHelper();


	/**
	 * Basic constructor with a default {@link ReactiveAdapterRegistry}.
	 * @param viewResolvers the resolver to use
	 * @param contentTypeResolver to determine the requested content type
	 */
	public ViewResolutionResultHandler(List<ViewResolver> viewResolvers,
			RequestedContentTypeResolver contentTypeResolver) {

		this(viewResolvers, contentTypeResolver, new ReactiveAdapterRegistry());
	}

	/**
	 * Constructor with an {@link ReactiveAdapterRegistry} instance.
	 * @param viewResolvers the view resolver to use
	 * @param contentTypeResolver to determine the requested content type
	 * @param registry for adaptation to reactive types
	 */
	public ViewResolutionResultHandler(List<ViewResolver> viewResolvers,
			RequestedContentTypeResolver contentTypeResolver, ReactiveAdapterRegistry registry) {

		super(contentTypeResolver, registry);
		this.viewResolvers.addAll(viewResolvers);
		AnnotationAwareOrderComparator.sort(this.viewResolvers);
	}


	/**
	 * Return a read-only list of view resolvers.
	 */
	public List<ViewResolver> getViewResolvers() {
		return Collections.unmodifiableList(this.viewResolvers);
	}

	/**
	 * Set the default views to consider always when resolving view names and
	 * trying to satisfy the best matching content type.
	 */
	public void setDefaultViews(List<View> defaultViews) {
		this.defaultViews.clear();
		if (defaultViews != null) {
			this.defaultViews.addAll(defaultViews);
		}
	}

	/**
	 * Return the configured default {@code View}'s.
	 */
	public List<View> getDefaultViews() {
		return this.defaultViews;
	}

	@Override
	public boolean supports(HandlerResult result) {
		if (hasModelAnnotation(result.getReturnTypeSource())) {
			return true;
		}
		Class<?> type = result.getReturnType().getRawClass();
		ReactiveAdapter adapter = getAdapter(result);
		if (adapter != null) {
			if (adapter.isNoValue()) {
				return true;
			}
			type = result.getReturnType().getGeneric(0).getRawClass();
		}
		return (CharSequence.class.isAssignableFrom(type) || View.class.isAssignableFrom(type) ||
				Model.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type) ||
				!BeanUtils.isSimpleProperty(type));
	}

	private boolean hasModelAnnotation(MethodParameter parameter) {
		return parameter.hasMethodAnnotation(ModelAttribute.class);
	}

	@Override
	@SuppressWarnings("unchecked")
	public Mono<Void> handleResult(ServerWebExchange exchange, HandlerResult result) {

		Mono<Object> valueMono;
		ResolvableType valueType;
		ReactiveAdapter adapter = getAdapter(result);

		if (adapter != null) {
			Assert.isTrue(!adapter.isMultiValue(), "Only single-value async return type supported.");
			valueMono = result.getReturnValue()
					.map(value -> Mono.from(adapter.toPublisher(value))).orElse(Mono.empty());
			valueType = adapter.isNoValue() ?
					ResolvableType.forClass(Void.class) : result.getReturnType().getGeneric(0);
		}
		else {
			valueMono = Mono.justOrEmpty(result.getReturnValue());
			valueType = result.getReturnType();
		}

		return valueMono
				.otherwiseIfEmpty(exchange.isNotModified() ? Mono.empty() : NO_VALUE_MONO)
				.then(returnValue -> {

					Mono<List<View>> viewsMono;
					Model model = result.getModel();
					MethodParameter parameter = result.getReturnTypeSource();

					Locale acceptLocale = exchange.getRequest().getHeaders().getAcceptLanguageAsLocale();
					Locale locale = acceptLocale != null ? acceptLocale : Locale.getDefault();

					Class<?> clazz = valueType.getRawClass();
					if (clazz == null) {
						clazz = returnValue.getClass();
					}

					if (returnValue == NO_VALUE || Void.class.equals(clazz) || void.class.equals(clazz)) {
						viewsMono = resolveViews(getDefaultViewName(exchange), locale);
					}
					else if (Model.class.isAssignableFrom(clazz)) {
						model.addAllAttributes(((Model) returnValue).asMap());
						viewsMono = resolveViews(getDefaultViewName(exchange), locale);
					}
					else if (Map.class.isAssignableFrom(clazz)) {
						model.addAllAttributes((Map<String, ?>) returnValue);
						viewsMono = resolveViews(getDefaultViewName(exchange), locale);
					}
					else if (View.class.isAssignableFrom(clazz)) {
						viewsMono = Mono.just(Collections.singletonList((View) returnValue));
					}
					else if (CharSequence.class.isAssignableFrom(clazz) && !hasModelAnnotation(parameter)) {
						viewsMono = resolveViews(returnValue.toString(), locale);
					}
					else {
						String name = getNameForReturnValue(clazz, parameter);
						model.addAttribute(name, returnValue);
						viewsMono = resolveViews(getDefaultViewName(exchange), locale);
					}

					addBindingResult(result.getBindingContext(), exchange);

					return viewsMono.then(views -> render(views, model.asMap(), exchange));
				});
	}

	/**
	 * Select a default view name when a controller did not specify it.
	 * Use the request path the leading and trailing slash stripped.
	 */
	private String getDefaultViewName(ServerWebExchange exchange) {
		String path = this.pathHelper.getLookupPathForRequest(exchange);
		if (path.startsWith("/")) {
			path = path.substring(1);
		}
		if (path.endsWith("/")) {
			path = path.substring(0, path.length() - 1);
		}
		return StringUtils.stripFilenameExtension(path);
	}

	private Mono<List<View>> resolveViews(String viewName, Locale locale) {
		return Flux.fromIterable(getViewResolvers())
				.concatMap(resolver -> resolver.resolveViewName(viewName, locale))
				.collectList()
				.map(views -> {
					if (views.isEmpty()) {
						throw new IllegalStateException(
								"Could not resolve view with name '" + viewName + "'.");
					}
					views.addAll(getDefaultViews());
					return views;
				});
	}

	/**
	 * Return the name of a model attribute return value based on the method
	 * {@code @ModelAttribute} annotation, if present, or derived from the type
	 * of the return value otherwise.
	 */
	private String getNameForReturnValue(Class<?> returnValueType, MethodParameter returnType) {
		ModelAttribute annotation = returnType.getMethodAnnotation(ModelAttribute.class);
		if (annotation != null && StringUtils.hasText(annotation.value())) {
			return annotation.value();
		}
		// TODO: Conventions does not deal with async wrappers
		return ClassUtils.getShortNameAsProperty(returnValueType);
	}



	private void addBindingResult(BindingContext context, ServerWebExchange exchange) {
		Map<String, Object> model = context.getModel().asMap();
		model.keySet().stream()
				.filter(name -> isBindingCandidate(name, model.get(name)))
				.filter(name -> !model.containsKey(BindingResult.MODEL_KEY_PREFIX + name))
				.forEach(name -> {
					WebExchangeDataBinder binder = context.createDataBinder(exchange, model.get(name), name);
					model.put(BindingResult.MODEL_KEY_PREFIX + name, binder.getBindingResult());
				});
	}

	private boolean isBindingCandidate(String name, Object value) {
		return !name.startsWith(BindingResult.MODEL_KEY_PREFIX) && value != null &&
				!value.getClass().isArray() && !(value instanceof Collection) &&
				!(value instanceof Map) && !BeanUtils.isSimpleValueType(value.getClass());
	}

	private Mono<? extends Void> render(List<View> views, Map<String, Object> model,
			ServerWebExchange exchange) {

		List<MediaType> mediaTypes = getMediaTypes(views);
		MediaType bestMediaType = selectMediaType(exchange, () -> mediaTypes);
		if (bestMediaType != null) {
			for (View view : views) {
				for (MediaType mediaType : view.getSupportedMediaTypes()) {
					if (mediaType.isCompatibleWith(bestMediaType)) {
						return view.render(model, mediaType, exchange);
					}
				}
			}
		}
		throw new NotAcceptableStatusException(mediaTypes);
	}

	private List<MediaType> getMediaTypes(List<View> views) {
		return views.stream()
				.flatMap(view -> view.getSupportedMediaTypes().stream())
				.collect(Collectors.toList());
	}

}
