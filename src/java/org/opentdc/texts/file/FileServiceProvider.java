/**
 * The MIT License (MIT)
 *
 * Copyright (c) 2015 Arbalo AG
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.opentdc.texts.file;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import javax.servlet.ServletContext;

import org.opentdc.file.AbstractFileServiceProvider;
import org.opentdc.texts.TextModel;
import org.opentdc.texts.ServiceProvider;
import org.opentdc.service.exception.DuplicateException;
import org.opentdc.service.exception.InternalServerErrorException;
import org.opentdc.service.exception.NotFoundException;
import org.opentdc.service.exception.ValidationException;
import org.opentdc.util.PrettyPrinter;

/**
 * A file-based or transient implementation of the Texts service.
 * @author Bruno Kaiser
 *
 */
public class FileServiceProvider extends AbstractFileServiceProvider<TextModel> implements ServiceProvider {
	
	private static Map<String, TextModel> index = null;
	private static final Logger logger = Logger.getLogger(FileServiceProvider.class.getName());

	/**
	 * Constructor.
	 * @param context the servlet context.
	 * @param prefix the simple class name of the service provider
	 * @throws IOException
	 */
	public FileServiceProvider(
		ServletContext context, 
		String prefix
	) throws IOException {
		super(context, prefix);
		if (index == null) {
			index = new HashMap<String, TextModel>();
			List<TextModel> _texts = importJson();
			for (TextModel _text : _texts) {
				index.put(_text.getId(), _text);
			}
			logger.info(_texts.size() + " Texts imported.");
		}
	}

	/* (non-Javadoc)
	 * @see org.opentdc.texts.ServiceProvider#list(java.lang.String, java.lang.String, long, long)
	 */
	@Override
	public ArrayList<TextModel> list(
		String queryType,
		String query,
		int position,
		int size
	) {
		ArrayList<TextModel> _texts = new ArrayList<TextModel>(index.values());
		Collections.sort(_texts, TextModel.TextComparator);
		ArrayList<TextModel> _selection = new ArrayList<TextModel>();
		for (int i = 0; i < _texts.size(); i++) {
			if (i >= position && i < (position + size)) {
				_selection.add(_texts.get(i));
			}			
		}
		logger.info("list(<" + query + ">, <" + queryType + 
				">, <" + position + ">, <" + size + ">) -> " + _selection.size() + " texts.");
		return _selection;
	}

	/* (non-Javadoc)
	 * @see org.opentdc.texts.ServiceProvider#create(org.opentdc.texts.TextModel)
	 */
	@Override
	public TextModel create(
		TextModel text) 
	throws DuplicateException, ValidationException {
		logger.info("create(" + PrettyPrinter.prettyPrintAsJSON(text) + ")");
		String _id = text.getId();
		if (_id == null || _id == "") {
			_id = UUID.randomUUID().toString();
		} else {
			if (index.get(_id) != null) {
				// object with same ID exists already
				throw new DuplicateException("text <" + _id + "> exists already.");
			}
			else { 	// a new ID was set on the client; we do not allow this
				throw new ValidationException("text <" + _id + 
					"> contains an ID generated on the client. This is not allowed.");
			}
		}
		// enforce mandatory fields
		if (text.getTitle() == null || text.getTitle().length() == 0) {
			throw new ValidationException("text <" + _id + 
					"> must contain a valid title.");
		}

		text.setId(_id);
		Date _date = new Date();
		text.setCreatedAt(_date);
		text.setCreatedBy(getPrincipal());
		text.setModifiedAt(_date);
		text.setModifiedBy(getPrincipal());
		index.put(_id, text);
		logger.info("create(" + PrettyPrinter.prettyPrintAsJSON(text) + ")");
		if (isPersistent) {
			exportJson(index.values());
		}
		return text;
	}

	/* (non-Javadoc)
	 * @see org.opentdc.texts.ServiceProvider#read(java.lang.String)
	 */
	@Override
	public TextModel read(
		String id) 
	throws NotFoundException {
		TextModel _text = index.get(id);
		if (_text == null) {
			throw new NotFoundException("no text with ID <" + id
					+ "> was found.");
		}
		logger.info("read(" + id + ") -> " + PrettyPrinter.prettyPrintAsJSON(_text));
		return _text;
	}

	/* (non-Javadoc)
	 * @see org.opentdc.texts.ServiceProvider#update(java.lang.String, org.opentdc.texts.TextModel)
	 */
	@Override
	public TextModel update(
		String id, 
		TextModel text
	) throws NotFoundException, ValidationException {
		TextModel _text = index.get(id);
		if(_text == null) {
			throw new NotFoundException("no text with ID <" + id
					+ "> was found.");
		} 
		if (! _text.getCreatedAt().equals(text.getCreatedAt())) {
			logger.warning("text <" + id + ">: ignoring createdAt value <" + text.getCreatedAt().toString() + 
					"> because it was set on the client.");
		}
		if (! _text.getCreatedBy().equalsIgnoreCase(text.getCreatedBy())) {
			logger.warning("text <" + id + ">: ignoring createdBy value <" + text.getCreatedBy() +
					"> because it was set on the client.");
		}
		_text.setTitle(text.getTitle());
		_text.setDescription(text.getDescription());
		_text.setModifiedAt(new Date());
		_text.setModifiedBy(getPrincipal());
		index.put(id, _text);
		logger.info("update(" + id + ") -> " + PrettyPrinter.prettyPrintAsJSON(_text));
		if (isPersistent) {
			exportJson(index.values());
		}
		return _text;
	}

	/* (non-Javadoc)
	 * @see org.opentdc.texts.ServiceProvider#delete(java.lang.String)
	 */
	@Override
	public void delete(
		String id) 
	throws NotFoundException, InternalServerErrorException {
		TextModel _text = index.get(id);
		if (_text == null) {
			throw new NotFoundException("text <" + id
					+ "> was not found.");
		}
		if (index.remove(id) == null) {
			throw new InternalServerErrorException("text <" + id
					+ "> can not be removed, because it does not exist in the index");
		}
		logger.info("delete(" + id + ")");
		if (isPersistent) {
			exportJson(index.values());
		}
	}
}
