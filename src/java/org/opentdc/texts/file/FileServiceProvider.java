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
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import javax.servlet.ServletContext;

import org.opentdc.file.AbstractFileServiceProvider;
import org.opentdc.texts.SingleLangText;
import org.opentdc.texts.TextModel;
import org.opentdc.texts.ServiceProvider;
import org.opentdc.service.LocalizedTextModel;
import org.opentdc.service.exception.DuplicateException;
import org.opentdc.service.exception.InternalServerErrorException;
import org.opentdc.service.exception.NotFoundException;
import org.opentdc.service.exception.ValidationException;
import org.opentdc.util.LanguageCode;
import org.opentdc.util.PrettyPrinter;

/**
 * A file-based or transient implementation of the Texts service.
 * @author Bruno Kaiser
 *
 */
public class FileServiceProvider extends AbstractFileServiceProvider<MultiLangText> implements ServiceProvider {
	private static Map<String, MultiLangText> index = null;
	private static Map<String, LocalizedTextModel> textIndex = null;	
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
			index = new ConcurrentHashMap<String, MultiLangText>();
			textIndex = new ConcurrentHashMap<String, LocalizedTextModel>();
			List<MultiLangText> _texts = importJson();
			for (MultiLangText _text : _texts) {
				index.put(_text.getModel().getId(), _text);
				for (LocalizedTextModel _tm : _text.getLocalizedTexts()) {
					textIndex.put(_tm.getId(), _tm);
				}
			}
			logger.info("indexed " +
					index.size() + " multiLangTexts and " +
					textIndex.size() + " localized texts.");
		}
	}

	/* (non-Javadoc)
	 * @see org.opentdc.texts.ServiceProvider#list(java.lang.String, java.lang.String, long, long)
	 */
	@Override
	public ArrayList<SingleLangText> list(
		String queryType,
		String query,
		int position,
		int size
	) {
		ArrayList<SingleLangText> _texts = new ArrayList<SingleLangText>();
		LocalizedTextModel _ltm = null;
		LanguageCode _lc = LanguageCode.getLanguageCodeFromQuery(query);
		for (MultiLangText _multiLangText : index.values()) {
			if (_lc != null) {	// return only the texts in this specific language
				_ltm = _multiLangText.getLocalizedText(_lc);
				if (_ltm != null) {
					_texts.add(new SingleLangText(_multiLangText.getModel().getId(), _ltm, getPrincipal()));
				}
			} else {	// return all texts in all languages
				List<LocalizedTextModel> _locTexts = _multiLangText.getLocalizedTexts();
				for (LocalizedTextModel _lm : _locTexts) {
					_texts.add(new SingleLangText(_multiLangText.getModel().getId(), _lm, getPrincipal()));
				}
			}
		}
		Collections.sort(_texts, SingleLangText.TextComparator);
		ArrayList<SingleLangText> _selection = new ArrayList<SingleLangText>();
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
		MultiLangText _multiLangText = null;
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
		_multiLangText = new MultiLangText();
		_multiLangText.setModel(text);
		index.put(_id, _multiLangText);
		logger.info("create(" + PrettyPrinter.prettyPrintAsJSON(text) + ")");
		if (isPersistent) {
			exportJson(index.values());
		}
		return _multiLangText.getModel();
	}

	/* (non-Javadoc)
	 * @see org.opentdc.texts.ServiceProvider#read(java.lang.String)
	 */
	@Override
	public TextModel read(
		String id) 
	throws NotFoundException {
		TextModel _text = readMultiLangText(id).getModel();
		logger.info("read(" + id + ") -> " + PrettyPrinter.prettyPrintAsJSON(_text));
		return _text;
	}

	/**
	 * Retrieve a MultiLangText from the index.
	 * @param id
	 * @return the MultiLangText
	 * @throws NotFoundException if the index did not contain a MultiLangText with this id
	 */
	private MultiLangText readMultiLangText(
			String id) 
				throws NotFoundException {
		MultiLangText _textedTag = index.get(id);
		if (_textedTag == null) {
			throw new NotFoundException("tag <" + id
					+ "> was not found.");
		}
		logger.info("readTextedTag(" + id + ") -> " + PrettyPrinter.prettyPrintAsJSON(_textedTag));
		return _textedTag;
	}

	/* (non-Javadoc)
	 * @see org.opentdc.texts.ServiceProvider#update(java.lang.String, org.opentdc.texts.TextModel)
	 */
	@Override
	public TextModel update(
		String id, 
		TextModel text
	) throws NotFoundException, ValidationException {
		MultiLangText _multiLangText = readMultiLangText(id);
		TextModel _text = _multiLangText.getModel();
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
		_multiLangText.setModel(_text);
		index.put(id, _multiLangText);
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
		MultiLangText _multiLangText = readMultiLangText(id);  // throws NotFound
		if (index.remove(id) == null) {
			throw new InternalServerErrorException("text <" + id
					+ "> can not be removed, because it does not exist in the index");
		} else {		// remove was ok
			// remove all LocalizedTexts members
			for (LocalizedTextModel _ltm : _multiLangText.getLocalizedTexts()) {
				if (textIndex.remove(_ltm.getId()) == null) {
					throw new InternalServerErrorException("tag <" + id + 
							">: LocalizedText <" + _ltm.getId() + 
							"> could not be removed, because it does not exist in the index.");
				} else {
					logger.info("delete(" + id + "): LocalizedText <" + _ltm.getId() + "> removed from the index.");
				}
			}
		}
		logger.info("delete(" + id + ") -> text removed from index.");
		if (isPersistent) {
			exportJson(index.values());
		}
	}
	
	/************************************** localized texts (lang) ************************************/
	/* (non-Javadoc)
	 * @see org.opentdc.texts.ServiceProvider#listTexts(java.lang.String, java.lang.String, java.lang.String, int, int)
	 */
	@Override
	public List<LocalizedTextModel> listTexts(
			String tid, 
			String queryType,
			String query, 
			int position, 
			int size) {
		List<LocalizedTextModel> _localizedTexts = readMultiLangText(tid).getLocalizedTexts();
		Collections.sort(_localizedTexts, LocalizedTextModel.LocalizedTextComparator);
		
		ArrayList<LocalizedTextModel> _selection = new ArrayList<LocalizedTextModel>();
		for (int i = 0; i < _localizedTexts.size(); i++) {
			if (i >= position && i < (position + size)) {
				_selection.add(_localizedTexts.get(i));
			}
		}
		logger.info("listTexts(<" + tid + ">, <" + query + ">, <" + queryType + 
				">, <" + position + ">, <" + size + ">) -> " + _selection.size()
				+ " values");
		return _selection;
	}

	/* (non-Javadoc)
	 * @see org.opentdc.texts.ServiceProvider#createText(java.lang.String, org.opentdc.service.LocalizedTextModel)
	 */
	@Override
	public LocalizedTextModel createText(
			String tid, 
			LocalizedTextModel tag)
			throws DuplicateException, ValidationException {
		if (tag.getText() == null || tag.getText().isEmpty()) {
			throw new ValidationException("LocalizedText <" + tid + "/lang/" + tag.getId() + 
					"> must contain a valid text.");
		}
		// enforce that the title is a single word
		StringTokenizer _tokenizer = new StringTokenizer(tag.getText());
		if (_tokenizer.countTokens() != 1) {
			throw new ValidationException("LocalizedText <" + tid + "/lang/" + tag.getId() + 
					"> must consist of exactly one word <" + tag.getText() + "> (is " + _tokenizer.countTokens() + ").");
		}
		MultiLangText _multiLangText = readMultiLangText(tid);
		if (tag.getLanguageCode() == null) {
			throw new ValidationException("LocalizedText <" + tid + "/lang/" + tag.getId() + 
					"> must contain a LanguageCode.");
		}
		if (_multiLangText.containsLocalizedText(tag.getLanguageCode())) {
			throw new DuplicateException("LocalizedText with LanguageCode <" + tag.getLanguageCode() + 
					"> exists already in tag <" + tid + ">.");
		}
		String _id = tag.getId();
		if (_id == null || _id.isEmpty()) {
			_id = UUID.randomUUID().toString();
		} else {
			if (textIndex.get(_id) != null) {
				throw new DuplicateException("LocalizedText with id <" + _id + 
						"> exists alreday in index.");
			}
			else {
				throw new ValidationException("LocalizedText <" + _id +
						"> contains an ID generated on the client. This is not allowed.");
			}
		}

		tag.setId(_id);
		Date _date = new Date();
		tag.setCreatedAt(_date);
		tag.setCreatedBy(getPrincipal());
		tag.setModifiedAt(_date);
		tag.setModifiedBy(getPrincipal());
		
		textIndex.put(_id, tag);
		_multiLangText.addText(tag);
		logger.info("createText(" + tid + "/lang/" + tag.getId() + ") -> " + PrettyPrinter.prettyPrintAsJSON(tag));
		if (isPersistent) {
			exportJson(index.values());
		}
		return tag;
	}

	/* (non-Javadoc)
	 * @see org.opentdc.texts.ServiceProvider#readText(java.lang.String, java.lang.String)
	 */
	@Override
	public LocalizedTextModel readText(
			String tid, 
			String lid)
			throws NotFoundException {
		readMultiLangText(tid);
		LocalizedTextModel _localizedText = textIndex.get(lid);
		if (_localizedText == null) {
			throw new NotFoundException("LocalizedText <" + tid + "/lang/" + lid +
					"> was not found.");
		}
		logger.info("readText(" + tid + "/lang/" + lid + ") -> "
				+ PrettyPrinter.prettyPrintAsJSON(_localizedText));
		return _localizedText;
	}

	/* (non-Javadoc)
	 * @see org.opentdc.texts.ServiceProvider#updateText(java.lang.String, java.lang.String, org.opentdc.service.LocalizedTextModel)
	 */
	@Override
	public LocalizedTextModel updateText(
			String tid, 
			String lid,
			LocalizedTextModel tag) 
					throws NotFoundException, ValidationException {
		readMultiLangText(tid);
		LocalizedTextModel _localizedText = textIndex.get(lid);
		if (_localizedText == null) {
			throw new NotFoundException("LocalizedText <" + tid + "/lang/" + lid +
					"> was not found.");
		}
		if (tag.getText() == null || tag.getText().isEmpty()) {
			throw new ValidationException("LocalizedText <" + tid + "/lang/" + lid + 
					"> must contain a valid text.");
		}
		// enforce that the title is a single word
		StringTokenizer _tokenizer = new StringTokenizer(tag.getText());
		if (_tokenizer.countTokens() != 1) {
			throw new ValidationException("LocalizedText <" + tid + "/lang/" + lid + 
					"> must consist of exactly one word <" + tag.getText() + "> (is " + _tokenizer.countTokens() + ").");
		}
		if (! _localizedText.getCreatedAt().equals(tag.getCreatedAt())) {
			logger.warning("LocalizedText <" + tid + "/lang/" + lid + ">: ignoring createAt value <" 
					+ tag.getCreatedAt().toString() + "> because it was set on the client.");
		}
		if (! _localizedText.getCreatedBy().equalsIgnoreCase(tag.getCreatedBy())) {
			logger.warning("LocalizedText <" + tid + "/lang/" + lid + ">: ignoring createBy value <"
					+ tag.getCreatedBy() + "> because it was set on the client.");
		}
		if (_localizedText.getLanguageCode() != tag.getLanguageCode()) {
			throw new ValidationException("LocalizedText <" + tid + "/lang/" + lid + 
					">: it is not allowed to change the LanguageCode.");
		}
		_localizedText.setText(tag.getText());
		_localizedText.setModifiedAt(new Date());
		_localizedText.setModifiedBy(getPrincipal());
		textIndex.put(lid, _localizedText);
		logger.info("updateText(" + tid + ", " + lid + ") -> " + PrettyPrinter.prettyPrintAsJSON(_localizedText));
		if (isPersistent) {
			exportJson(index.values());
		}
		return _localizedText;
	}

	/* (non-Javadoc)
	 * @see org.opentdc.texts.ServiceProvider#deleteText(java.lang.String, java.lang.String)
	 */
	@Override
	public void deleteText(
			String tid, 
			String lid) 
					throws NotFoundException, InternalServerErrorException {
		MultiLangText _multiLangText = readMultiLangText(tid);
		LocalizedTextModel _localizedText = textIndex.get(lid);
		if (_localizedText == null) {
			throw new NotFoundException("LocalizedText <" + tid + "/lang/" + lid +
					"> was not found.");
		}
		
		// 1) remove the LocalizedText from its TextedTag
		if (_multiLangText.removeText(_localizedText) == false) {
			throw new InternalServerErrorException("LocalizedText <" + tid + "/lang/" + lid
					+ "> can not be removed, because it is an orphan.");
		}
		// 2) remove the LocalizedText from the index
		if (textIndex.remove(lid) == null) {
			throw new InternalServerErrorException("LocalizedText <" + tid + "/lang/" + lid
					+ "> can not be removed, because it does not exist in the index.");
		}
		
			
		logger.info("deleteText(" + tid + ", " + lid + ") -> OK");
		if (isPersistent) {
			exportJson(index.values());
		}		
	}
}
