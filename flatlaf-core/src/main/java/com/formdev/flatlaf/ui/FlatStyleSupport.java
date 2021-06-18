/*
 * Copyright 2021 FormDev Software GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.formdev.flatlaf.ui;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.UIManager;
import javax.swing.border.Border;
import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.util.StringUtils;

/**
 * Support for styling components in CSS syntax.
 *
 * @author Karl Tauber
 * @since TODO
 */
public class FlatStyleSupport
{
	/**
	 * Indicates that a field is intended to be used by FlatLaf styling support.
	 * <p>
	 * <strong>Do not rename fields annotated with this annotation.</strong>
	 */
	@Target(ElementType.FIELD)
	@Retention(RetentionPolicy.RUNTIME)
	public @interface Styleable {
	}

	/**
	 * Parses styles in CSS syntax ("key1: value1; key2: value2; ..."),
	 * converts the value strings into binary and invokes the given function
	 * to apply the properties.
	 *
	 * @param oldStyleValues map of old values modified by the previous invocation, or {@code null}
	 * @param style the style in CSS syntax as string, or a Map, or {@code null}
	 * @param applyProperty function that is invoked to apply the properties;
	 *                      first parameter is the key, second the binary value;
	 *                      the function must return the old value
	 * @return map of old values modified by the given style, or {@code null}
	 * @throws UnknownStyleException on unknown style keys
	 * @throws IllegalArgumentException on syntax errors
	 * @throws ClassCastException if value type does not fit to expected type 
	 */
	public static Map<String, Object> parseAndApply( Map<String, Object> oldStyleValues,
		Object style, BiFunction<String, Object, Object> applyProperty )
			throws UnknownStyleException, IllegalArgumentException
	{
		// restore previous values
		if( oldStyleValues != null ) {
			for( Map.Entry<String, Object> e : oldStyleValues.entrySet() )
				applyProperty.apply( e.getKey(), e.getValue() );
		}

		// ignore empty style
		if( style == null )
			return null;

		if( style instanceof String ) {
			// handle style in CSS syntax
			String str = (String) style;
			if( str.trim().isEmpty() )
				return null;

			return applyStyle( parse( str ), applyProperty );
		} else if( style instanceof Map ) {
			// handle style of type Map
			@SuppressWarnings( "unchecked" )
			Map<String, Object> map = (Map<String, Object>) style;
			return applyStyle( map, applyProperty );
		} else
			return null;
	}

	private static Map<String, Object> applyStyle( Map<String, Object> style,
		BiFunction<String, Object, Object> applyProperty )
	{
		if( style.isEmpty() )
			return null;

		Map<String, Object> oldValues = new HashMap<>();
		for( Map.Entry<String, Object> e : style.entrySet() ) {
			String key = e.getKey();
			Object newValue = e.getValue();

			Object oldValue = applyProperty.apply( key, newValue );
			oldValues.put( key, oldValue );
		}
		return oldValues;
	}

	/**
	 * Parses styles in CSS syntax ("key1: value1; key2: value2; ..."),
	 * converts the value strings into binary and returns all key/value pairs as map.
	 *
	 * @param style the style in CSS syntax, or {@code null}
	 * @return map of parsed styles, or {@code null}
	 * @throws IllegalArgumentException on syntax errors
	 */
	public static Map<String, Object> parse( String style )
		throws IllegalArgumentException
	{
		if( style == null || style.trim().isEmpty() )
			return null;

		Map<String, Object> map = null;

		// split style into parts and process them
		for( String part : StringUtils.split( style, ';' ) ) {
			// ignore empty parts
			part = part.trim();
			if( part.isEmpty() )
				continue;

			// find separator colon
			int sepIndex = part.indexOf( ':' );
			if( sepIndex < 0 )
				throw new IllegalArgumentException( "missing colon in '" + part + "'" );

			// split into key and value
			String key = part.substring( 0, sepIndex ).trim();
			String value = part.substring( sepIndex + 1 ).trim();
			if( key.isEmpty() )
				throw new IllegalArgumentException( "missing key in '" + part + "'" );
			if( value.isEmpty() )
				throw new IllegalArgumentException( "missing value in '" + part + "'" );

			// parse value string and convert it into binary value
			if( map == null )
				map = new LinkedHashMap<>();
			map.put( key, parseValue( key, value ) );
		}

		return map;
	}

	private static Object parseValue( String key, String value ) {
		if( value.startsWith( "$" ) )
			return UIManager.get( value.substring( 1 ) );

		return FlatLaf.parseDefaultsValue( key, value );
	}

	/**
	 * Applies the given value to an annotated field of the given object.
	 * The field must be annotated with {@link Styleable}.
	 *
	 * @param obj the object
	 * @param key the name of the field
	 * @param value the new value
	 * @return the old value of the field
	 * @throws UnknownStyleException if object does not have a annotated field with given name
	 * @throws IllegalArgumentException if value type does not fit to expected type 
	 */
	public static Object applyToAnnotatedObject( Object obj, String key, Object value )
		throws UnknownStyleException, IllegalArgumentException
	{
		Class<?> cls = obj.getClass();

		for(;;) {
			try {
				Field f = cls.getDeclaredField( key );
				if( f.isAnnotationPresent( Styleable.class ) ) {
					if( Modifier.isFinal( f.getModifiers() ) )
						throw new IllegalArgumentException( "field '" + cls.getName() + "." + key + "' is final" );

					try {
						// necessary to access protected fields in other packages
						f.setAccessible( true );

						// get old value and set new value
						Object oldValue = f.get( obj );
						f.set( obj, value );
						return oldValue;
					} catch( IllegalAccessException ex ) {
						throw new IllegalArgumentException( "failed to access field '" + cls.getName() + "." + key + "'" );
					}
				}
			} catch( NoSuchFieldException ex ) {
				// field not found in class --> try superclass
			}

			cls = cls.getSuperclass();
			if( cls == null )
				throw new UnknownStyleException( key );

			String superclassName = cls.getName();
			if( superclassName.startsWith( "java." ) || superclassName.startsWith( "javax." ) )
				throw new UnknownStyleException( key );
		}
	}

	public static Object getStyle( JComponent c ) {
		return c.getClientProperty( FlatClientProperties.COMPONENT_STYLE );
	}

	static Border cloneBorder( Border border ) {
		Class<? extends Border> borderClass = border.getClass();
		try {
			return borderClass.getDeclaredConstructor().newInstance();
		} catch( Exception ex ) {
			throw new IllegalArgumentException( "failed to clone border '" + borderClass.getName() + "'" );
		}
	}

	static Icon cloneIcon( Icon icon ) {
		Class<? extends Icon> iconClass = icon.getClass();
		try {
			return iconClass.getDeclaredConstructor().newInstance();
		} catch( Exception ex ) {
			throw new IllegalArgumentException( "failed to clone icon '" + iconClass.getName() + "'" );
		}
	}

	//---- class UnknownStyleException ----------------------------------------

	public static class UnknownStyleException
		extends IllegalArgumentException
	{
		public UnknownStyleException( String key ) {
			super( key );
		}

		@Override
		public String getMessage() {
			return "unknown style '" + super.getMessage() + "'";
		}
	}
}
