package org.jabref.model.entry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import javafx.beans.Observable;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.ObjectBinding;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableMap;

import org.jabref.model.FieldChange;
import org.jabref.model.database.BibDatabase;
import org.jabref.model.entry.event.EntryEventSource;
import org.jabref.model.entry.event.FieldAddedOrRemovedEvent;
import org.jabref.model.entry.event.FieldChangedEvent;
import org.jabref.model.entry.field.Field;
import org.jabref.model.entry.field.InternalField;
import org.jabref.model.entry.field.OrFields;
import org.jabref.model.entry.field.StandardField;
import org.jabref.model.entry.identifier.DOI;
import org.jabref.model.strings.LatexToUnicodeAdapter;
import org.jabref.model.strings.StringUtil;

import com.google.common.base.Strings;
import com.google.common.eventbus.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BibEntry implements Cloneable {

    public static final EntryType DEFAULT_TYPE = StandardEntryType.Misc;
    private static final Logger LOGGER = LoggerFactory.getLogger(BibEntry.class);
    private static final Pattern REMOVE_TRAILING_WHITESPACE = Pattern.compile("\\s+$");
    private final SharedBibEntryData sharedBibEntryData;
    /**
     * Map to store the words in every field
     */
    private final Map<Field, Set<String>> fieldsAsWords = new HashMap<>();
    /**
     * Cache that stores latex free versions of fields.
     */
    private final Map<Field, String> latexFreeFields = new ConcurrentHashMap<>();
    private final EventBus eventBus = new EventBus();
    private String id;
    private final ObjectProperty<EntryType> type = new SimpleObjectProperty<>(DEFAULT_TYPE);

    private ObservableMap<Field, String> fields = FXCollections.observableMap(new ConcurrentHashMap<>());
    private String parsedSerialization = "";
    private String commentsBeforeEntry = "";
    /**
     * Marks whether the complete serialization, which was read from file, should be used.
     *
     * Is set to false, if parts of the entry change. This causes the entry to be serialized based on the internal state (and not based on the old serialization)
     */
    private boolean changed;

    /**
     * Constructs a new BibEntry. The internal ID is set to IdGenerator.next()
     */
    public BibEntry() {
        this(IdGenerator.next(), DEFAULT_TYPE);

    }

    /**
     * Constructs a new BibEntry with the given ID and given type
     *
     * @param id   The ID to be used
     * @param type The type to set. May be null or empty. In that case, DEFAULT_TYPE is used.
     */
    private BibEntry(String id, EntryType type) {
        Objects.requireNonNull(id, "Every BibEntry must have an ID");

        this.id = id;
        setType(type);
        this.sharedBibEntryData = new SharedBibEntryData();
    }

    /**
     * Constructs a new BibEntry. The internal ID is set to IdGenerator.next()
     */
    public BibEntry(EntryType type) {
        this(IdGenerator.next(), type);
    }

    public Optional<FieldChange> setMonth(Month parsedMonth) {
        return setField(StandardField.MONTH, parsedMonth.getJabRefFormat());
    }

    public Optional<String> getResolvedFieldOrAlias(OrFields fields, BibDatabase database) {
        for (Field field : fields) {
            Optional<String> value = getResolvedFieldOrAlias(field, database);
            if (value.isPresent()) {
                return value;
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the text stored in the given field of the given bibtex entry
     * which belongs to the given database.
     * <p>
     * If a database is given, this function will try to resolve any string
     * references in the field-value.
     * Also, if a database is given, this function will try to find values for
     * unset fields in the entry linked by the "crossref" field, if any.
     *
     * @param field    The field to return the value of.
     * @param database maybenull
     *                 The database of the bibtex entry.
     * @return The resolved field value or null if not found.
     */
    public Optional<String> getResolvedFieldOrAlias(Field field, BibDatabase database) {
        if (InternalField.TYPE_HEADER.equals(field) || InternalField.OBSOLETE_TYPE_HEADER.equals(field)) {
            return Optional.of(type.get().getDisplayName());
        }

        if (InternalField.KEY_FIELD.equals(field)) {
            return getCiteKeyOptional();
        }

        Optional<String> result = getFieldOrAlias(field);

        // If this field is not set, and the entry has a crossref, try to look up the
        // field in the referred entry: Do not do this for the bibtex key.
        if (!result.isPresent() && (database != null)) {
            Optional<BibEntry> referred = database.getReferencedEntry(this);
            result = referred.flatMap(entry -> entry.getFieldOrAlias(field));
        }
        return result.map(resultText -> BibDatabase.getText(resultText, database));
    }

    /**
     * Returns this entry's ID.
     */
    public String getId() {
        return id;
    }

    /**
     * Sets this entry's ID, provided the database containing it
     * doesn't veto the change.
     *
     * @param id The ID to be used
     */
    public void setId(String id) {
        Objects.requireNonNull(id, "Every BibEntry must have an ID");

        String oldId = this.id;

        eventBus.post(new FieldChangedEvent(this, InternalField.INTERNAL_ID_FIELD, id, oldId));
        this.id = id;
        changed = true;
    }

    /**
     * Returns the cite key AKA citation key AKA BibTeX key, or null if it is not set.
     * Note: this is <emph>not</emph> the internal Id of this entry. The internal Id is always present, whereas the BibTeX key might not be present.
     */
    @Deprecated
    public String getCiteKey() {
        return fields.get(InternalField.KEY_FIELD);
    }

    /**
     * Sets the cite key AKA citation key AKA BibTeX key. Note: This is <emph>not</emph> the internal Id of this entry.
     * The internal Id is always present, whereas the BibTeX key might not be present.
     *
     * @param newCiteKey The cite key to set. Must not be null; use {@link #clearCiteKey()} to remove the cite key.
     */
    public Optional<FieldChange> setCiteKey(String newCiteKey) {
        return setField(InternalField.KEY_FIELD, newCiteKey);
    }

    public BibEntry withCiteKey(String newCiteKey) {
        setCiteKey(newCiteKey);
        return this;
    }

    public Optional<String> getCiteKeyOptional() {
        return Optional.ofNullable(fields.get(InternalField.KEY_FIELD));
    }

    public boolean hasCiteKey() {
        return !Strings.isNullOrEmpty(getCiteKey());
    }

    /**
     * Returns this entry's type.
     */
    public EntryType getType() {
        return type.getValue();
    }

    public ObjectProperty<EntryType> typeProperty() {
        return type;
    }

    /**
     * Sets this entry's type.
     */
    public Optional<FieldChange> setType(EntryType type) {
        return setType(type, EntryEventSource.LOCAL);
    }

    /**
     * Sets this entry's type.
     */
    public Optional<FieldChange> setType(EntryType newType, EntryEventSource eventSource) {
        Objects.requireNonNull(newType);

        EntryType oldType = type.get();
        if (newType.equals(oldType)) {
            return Optional.empty();
        }

        this.type.setValue(newType);
        changed = true;

        FieldChange change = new FieldChange(this, InternalField.TYPE_HEADER, oldType.getName(), newType.getName());
        eventBus.post(new FieldChangedEvent(change, eventSource));
        return Optional.of(change);
    }

    /**
     * Returns an set containing the names of all fields that are
     * set for this particular entry.
     *
     * @return a set of existing field names
     */
    public Set<Field> getFields() {
        return Collections.unmodifiableSet(fields.keySet());
    }

    /**
     * Returns the contents of the given field as an Optional.
     */
    public Optional<String> getField(Field field) {
        return Optional.ofNullable(fields.get(field));
    }

    /**
     * Returns true if the entry has the given field, or false if it is not set.
     */
    public boolean hasField(Field field) {
        return fields.containsKey(field);
    }

    /**
     * Internal method used to get the content of a field (or its alias)
     *
     * Used by {@link #getFieldOrAlias(Field)} and {@link #getFieldOrAliasLatexFree(Field)}
     *
     * @param field the field
     * @param getFieldInterface
     *
     * @return determined field value
     */
    private Optional<String> genericGetFieldOrAlias(Field field, GetFieldInterface getFieldInterface) {
        Optional<String> fieldValue = getFieldInterface.getValueForField(field);

        if (fieldValue.isPresent() && !fieldValue.get().isEmpty()) {
            return fieldValue;
        }

        // No value of this field found, so look at the alias
        Field aliasForField = EntryConverter.FIELD_ALIASES.get(field);

        if (aliasForField != null) {
            return getFieldInterface.getValueForField(aliasForField);
        }

        // Finally, handle dates
        if (StandardField.DATE.equals(field)) {
            Optional<Date> date = Date.parse(
                    getFieldInterface.getValueForField(StandardField.YEAR),
                    getFieldInterface.getValueForField(StandardField.MONTH),
                    getFieldInterface.getValueForField(StandardField.DAY));

            return date.map(Date::getNormalized);
        }

        if (StandardField.YEAR.equals(field) || StandardField.MONTH.equals(field) || StandardField.DAY.equals(field)) {
            Optional<String> date = getFieldInterface.getValueForField(StandardField.DATE);
            if (!date.isPresent()) {
                return Optional.empty();
            }

            Optional<Date> parsedDate = Date.parse(date.get());
            if (parsedDate.isPresent()) {
                if (StandardField.YEAR.equals(field)) {
                    return parsedDate.get().getYear().map(Object::toString);
                }
                if (StandardField.MONTH.equals(field)) {
                    return parsedDate.get().getMonth().map(Month::getJabRefFormat);
                }
                if (StandardField.DAY.equals(field)) {
                    return parsedDate.get().getDay().map(Object::toString);
                }
            } else {
                // Date field not in valid format
                LOGGER.debug("Could not parse date " + date.get());
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    public Optional<DOI> getDOI() {
        return getField(StandardField.DOI).flatMap(DOI::parse);
    }

    /**
     * Return the LaTeX-free contents of the given field or its alias an an Optional
     *
     * For details see also {@link #getFieldOrAlias(Field)}
     *
     * @param name the name of the field
     * @return  the stored latex-free content of the field (or its alias)
     */
    public Optional<String> getFieldOrAliasLatexFree(Field name) {
        return genericGetFieldOrAlias(name, this::getLatexFreeField);
    }

    /**
     * Returns the contents of the given field or its alias as an Optional
     * <p>
     * The following aliases are considered (old bibtex <-> new biblatex) based
     * on the biblatex documentation, chapter 2.2.5:<br>
     * address        <-> location <br>
     * annote         <-> annotation <br>
     * archiveprefix  <-> eprinttype <br>
     * journal        <-> journaltitle <br>
     * key            <-> sortkey <br>
     * pdf            <-> file <br
     * primaryclass   <-> eprintclass <br>
     * school         <-> institution <br>
     * These work bidirectional. <br>
     * </p>
     *
     * <p>
     * Special attention is paid to dates: (see the biblatex documentation,
     * chapter 2.3.8)
     * The fields 'year' and 'month' are used if the 'date'
     * field is empty. Conversely, getFieldOrAlias("year") also tries to
     * extract the year from the 'date' field (analogously for 'month').
     * </p>
     */
    public Optional<String> getFieldOrAlias(Field field) {
        return genericGetFieldOrAlias(field, this::getField);
    }

    /**
     * Sets a number of fields simultaneously. The given HashMap contains field
     * names as keys, each mapped to the value to set.
     */
    public void setField(Map<Field, String> fields) {
        Objects.requireNonNull(fields, "fields must not be null");

        fields.forEach(this::setField);
    }

    /**
     * Set a field, and notify listeners about the change.
     *
     * @param field        The field to set
     * @param value       The value to set
     * @param eventSource Source the event is sent from
     */
    public Optional<FieldChange> setField(Field field, String value, EntryEventSource eventSource) {
        Objects.requireNonNull(field, "field name must not be null");
        Objects.requireNonNull(value, "field value must not be null");

        if (value.isEmpty()) {
            return clearField(field);
        }

        String oldValue = getField(field).orElse(null);
        boolean isNewField = oldValue == null;
        if (value.equals(oldValue)) {
            return Optional.empty();
        }

        changed = true;

        fields.put(field, value.intern());
        invalidateFieldCache(field);

        FieldChange change = new FieldChange(this, field, oldValue, value);
        if (isNewField) {
            eventBus.post(new FieldAddedOrRemovedEvent(change, eventSource));
        } else {
            eventBus.post(new FieldChangedEvent(change, eventSource));
        }
        return Optional.of(change);
    }

    public Optional<FieldChange> setField(Field field, Optional<String> value, EntryEventSource eventSource) {
        if (value.isPresent()) {
            return setField(field, value.get(), eventSource);
        }
        return Optional.empty();
    }

    /**
     * Set a field, and notify listeners about the change.
     *
     * @param field  The field to set.
     * @param value The value to set.
     */
    public Optional<FieldChange> setField(Field field, String value) {
        return setField(field, value, EntryEventSource.LOCAL);
    }

    /**
     * Remove the mapping for the field name, and notify listeners about
     * the change.
     *
     * @param field The field to clear.
     */
    public Optional<FieldChange> clearField(Field field) {
        return clearField(field, EntryEventSource.LOCAL);
    }

    /**
     * Remove the mapping for the field name, and notify listeners about
     * the change including the {@link EntryEventSource}.
     *
     * @param field       the field to clear.
     * @param eventSource the source a new {@link FieldChangedEvent} should be posten from.
     */
    public Optional<FieldChange> clearField(Field field, EntryEventSource eventSource) {
        Optional<String> oldValue = getField(field);
        if (!oldValue.isPresent()) {
            return Optional.empty();
        }

        changed = true;

        fields.remove(field);
        invalidateFieldCache(field);

        FieldChange change = new FieldChange(this, field, oldValue.get(), null);
        eventBus.post(new FieldAddedOrRemovedEvent(change, eventSource));
        return Optional.of(change);
    }

    /**
     * Determines whether this entry has all the given fields present. If a non-null
     * database argument is given, this method will try to look up missing fields in
     * entries linked by the "crossref" field, if any.
     *
     * @param fields An array of field names to be checked.
     * @param database  The database in which to look up crossref'd entries, if any. This
     *                  argument can be null, meaning that no attempt will be made to follow crossrefs.
     * @return true if all fields are set or could be resolved, false otherwise.
     */
    public boolean allFieldsPresent(Collection<OrFields> fields, BibDatabase database) {
        return fields.stream().allMatch(field -> this.getResolvedFieldOrAlias(field, database).isPresent());
    }

    /**
     * Returns a clone of this entry. Useful for copying.
     * This will set a new ID for the cloned entry to be able to distinguish both copies.
     */
    @Override
    public Object clone() {
        BibEntry clone = new BibEntry(IdGenerator.next(), type.getValue());
        clone.fields = FXCollections.observableMap(new ConcurrentHashMap<>(fields));
        return clone;
    }

    /**
     * This returns a canonical BibTeX serialization. Special characters such as "{" or "&" are NOT escaped, but written
     * as is
     * <p>
     * Serializes all fields, even the JabRef internal ones. Does NOT serialize "KEY_FIELD" as field, but as key
     */
    @Override
    public String toString() {
        return CanonicalBibtexEntry.getCanonicalRepresentation(this);
    }

    /**
     * @param maxCharacters The maximum number of characters (additional
     *                      characters are replaced with "..."). Set to 0 to disable truncation.
     * @return A short textual description of the entry in the format:
     * Author1, Author2: Title (Year)
     */
    public String getAuthorTitleYear(int maxCharacters) {
        String[] s = new String[]{getField(StandardField.AUTHOR).orElse("N/A"), getField(StandardField.TITLE).orElse("N/A"),
                getField(StandardField.YEAR).orElse("N/A")};

        String text = s[0] + ": \"" + s[1] + "\" (" + s[2] + ')';
        if ((maxCharacters <= 0) || (text.length() <= maxCharacters)) {
            return text;
        }
        return text.substring(0, maxCharacters + 1) + "...";
    }

    /**
     * Returns the title of the given BibTeX entry as an Optional.
     *
     * @return an Optional containing the title of a BibTeX entry in case it exists, otherwise return an empty Optional.
     */
    public Optional<String> getTitle() {
        return getField(StandardField.TITLE);
    }

    /**
     * Will return the publication date of the given bibtex entry conforming to ISO 8601, i.e. either YYYY or YYYY-MM.
     *
     * @return will return the publication date of the entry or null if no year was found.
     */
    public Optional<Date> getPublicationDate() {
        return getFieldOrAlias(StandardField.DATE).flatMap(Date::parse);
    }

    public String getParsedSerialization() {
        return parsedSerialization;
    }

    public void setParsedSerialization(String parsedSerialization) {
        changed = false;
        this.parsedSerialization = parsedSerialization;
    }

    public void setCommentsBeforeEntry(String parsedComments) {
        // delete trailing whitespaces (between entry and text)
        this.commentsBeforeEntry = REMOVE_TRAILING_WHITESPACE.matcher(parsedComments).replaceFirst("");
    }

    public boolean hasChanged() {
        return changed;
    }

    public void setChanged(boolean changed) {
        this.changed = changed;
    }

    public Optional<FieldChange> putKeywords(List<String> keywords, Character delimiter) {
        Objects.requireNonNull(delimiter);
        return putKeywords(new KeywordList(keywords), delimiter);
    }

    public Optional<FieldChange> putKeywords(KeywordList keywords, Character delimiter) {
        Objects.requireNonNull(keywords);
        Optional<String> oldValue = this.getField(StandardField.KEYWORDS);

        if (keywords.isEmpty()) {
            // Clear keyword field
            if (oldValue.isPresent()) {
                return this.clearField(StandardField.KEYWORDS);
            } else {
                return Optional.empty();
            }
        }

        // Set new keyword field
        String newValue = keywords.getAsString(delimiter);
        return this.setField(StandardField.KEYWORDS, newValue);
    }

    /**
     * Check if a keyword already exists (case insensitive), if not: add it
     *
     * @param keyword Keyword to add
     */
    public void addKeyword(String keyword, Character delimiter) {
        Objects.requireNonNull(keyword, "keyword must not be null");

        if (keyword.isEmpty()) {
            return;
        }

        addKeyword(new Keyword(keyword), delimiter);
    }

    public void addKeyword(Keyword keyword, Character delimiter) {
        KeywordList keywords = this.getKeywords(delimiter);
        keywords.add(keyword);
        this.putKeywords(keywords, delimiter);
    }

    /**
     * Add multiple keywords to entry
     *
     * @param keywords Keywords to add
     */
    public void addKeywords(Collection<String> keywords, Character delimiter) {
        Objects.requireNonNull(keywords);
        keywords.forEach(keyword -> addKeyword(keyword, delimiter));
    }

    public KeywordList getKeywords(Character delimiter) {
        Optional<String> keywordsContent = getField(StandardField.KEYWORDS);
        return keywordsContent.map(content -> KeywordList.parse(content, delimiter)).orElse(new KeywordList());
    }

    public KeywordList getResolvedKeywords(Character delimiter, BibDatabase database) {
        Optional<String> keywordsContent = getResolvedFieldOrAlias(StandardField.KEYWORDS, database);
        return keywordsContent.map(content -> KeywordList.parse(content, delimiter)).orElse(new KeywordList());
    }

    public Optional<FieldChange> removeKeywords(KeywordList keywordsToRemove, Character keywordDelimiter) {
        KeywordList keywordList = getKeywords(keywordDelimiter);
        keywordList.removeAll(keywordsToRemove);
        return putKeywords(keywordList, keywordDelimiter);
    }

    public Optional<FieldChange> replaceKeywords(KeywordList keywordsToReplace, Keyword newValue,
            Character keywordDelimiter) {
        KeywordList keywordList = getKeywords(keywordDelimiter);
        keywordList.replaceAll(keywordsToReplace, newValue);

        return putKeywords(keywordList, keywordDelimiter);
    }

    public Collection<String> getFieldValues() {
        return fields.values();
    }

    public Map<Field, String> getFieldMap() {
        return fields;
    }

    public SharedBibEntryData getSharedBibEntryData() {
        return sharedBibEntryData;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if ((o == null) || (getClass() != o.getClass())) {
            return false;
        }
        BibEntry entry = (BibEntry) o;
        return Objects.equals(type.getValue(), entry.type.getValue())
                && Objects.equals(fields, entry.fields)
                && Objects.equals(commentsBeforeEntry, entry.commentsBeforeEntry);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type.getValue(), fields);
    }

    public void registerListener(Object object) {
        this.eventBus.register(object);
    }

    public void unregisterListener(Object object) {
        try {
            this.eventBus.unregister(object);
        } catch (IllegalArgumentException e) {
            // occurs if the event source has not been registered, should not prevent shutdown
            LOGGER.debug("Problem unregistering", e);
        }
    }

    public BibEntry withField(Field field, String value) {
        setField(field, value);
        return this;
    }

    /*
    * Returns user comments (arbitrary text before the entry), if they exist. If not, returns the empty String
     */
    public String getUserComments() {
        return commentsBeforeEntry;
    }

    public List<ParsedEntryLink> getEntryLinkList(Field field, BibDatabase database) {
        return getField(field).map(fieldValue -> EntryLinkList.parse(fieldValue, database))
                .orElse(Collections.emptyList());
    }

    public Optional<FieldChange> setEntryLinkList(Field field, List<ParsedEntryLink> list) {
        return setField(field, EntryLinkList.serialize(list));
    }

    public Set<String> getFieldAsWords(Field field) {
        Set<String> storedList = fieldsAsWords.get(field);
        if (storedList != null) {
            return storedList;
        } else {
            String fieldValue = fields.get(field);
            if (fieldValue == null) {
                return Collections.emptySet();
            } else {
                HashSet<String> words = new HashSet<>(StringUtil.getStringAsWords(fieldValue));
                fieldsAsWords.put(field, words);
                return words;
            }
        }
    }

    public Optional<FieldChange> clearCiteKey() {
        return clearField(InternalField.KEY_FIELD);
    }

    private void invalidateFieldCache(Field field) {
        latexFreeFields.remove(field);
        fieldsAsWords.remove(field);
    }

    public Optional<String> getLatexFreeField(Field field) {
        if (!hasField(field) && !InternalField.TYPE_HEADER.equals(field)) {
            return Optional.empty();
        } else if (latexFreeFields.containsKey(field)) {
            return Optional.ofNullable(latexFreeFields.get(field));
        } else if (InternalField.KEY_FIELD.equals(field)) {
            // the key field should not be converted
            Optional<String> citeKey = getCiteKeyOptional();
            latexFreeFields.put(field, citeKey.get());
            return citeKey;
        } else if (InternalField.TYPE_HEADER.equals(field)) {
            String typeName = type.get().getDisplayName();
            latexFreeFields.put(field, typeName);
            return Optional.of(typeName);
        } else {
            String latexFreeField = LatexToUnicodeAdapter.format(getField(field).get()).intern();
            latexFreeFields.put(field, latexFreeField);
            return Optional.of(latexFreeField);
        }
    }

    public Optional<FieldChange> setFiles(List<LinkedFile> files) {
        Optional<String> oldValue = this.getField(StandardField.FILE);
        String newValue = FileFieldWriter.getStringRepresentation(files);

        if (oldValue.isPresent() && oldValue.get().equals(newValue)) {
            return Optional.empty();
        }

        return this.setField(StandardField.FILE, newValue);
    }

    /**
     * Gets a list of linked files.
     *
     * @return the list of linked files, is never null but can be empty.
     * Changes to the underlying list will have no effect on the entry itself. Use {@link #addFile(LinkedFile)}
     */
    public List<LinkedFile> getFiles() {
        //Extract the path
        Optional<String> oldValue = getField(StandardField.FILE);
        if (!oldValue.isPresent()) {
            return new ArrayList<>(); //Return new ArrayList because emptyList is immutable
        }

        return FileFieldParser.parse(oldValue.get());
    }

    public void setDate(Date date) {
        date.getYear().ifPresent(year -> setField(StandardField.YEAR, year.toString()));
        date.getMonth().ifPresent(this::setMonth);
        date.getDay().ifPresent(day -> setField(StandardField.DAY, day.toString()));
    }

    public Optional<Month> getMonth() {
        return getFieldOrAlias(StandardField.MONTH).flatMap(Month::parse);
    }

    public ObjectBinding<String> getFieldBinding(Field field) {
        //noinspection unchecked
        return Bindings.valueAt(fields, field);
    }

    public ObjectBinding<String> getCiteKeyBinding() {
        return getFieldBinding(InternalField.KEY_FIELD);
    }

    public Optional<FieldChange> addFile(LinkedFile file) {
        List<LinkedFile> linkedFiles = getFiles();
        linkedFiles.add(file);
        return setFiles(linkedFiles);
    }

    public ObservableMap<Field, String> getFieldsObservable() {
        return fields;
    }

    /**
     * Returns a list of observables that represent the data of the entry.
     */
    public Observable[] getObservables() {
        return new Observable[] {fields};
    }

    private interface GetFieldInterface {
        Optional<String> getValueForField(Field field);
    }

}
