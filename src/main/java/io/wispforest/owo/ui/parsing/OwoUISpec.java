
package io.wispforest.owo.ui.parsing;

import io.wispforest.owo.Owo;
import io.wispforest.owo.ui.OwoUIAdapter;
import io.wispforest.owo.ui.definitions.Component;
import io.wispforest.owo.ui.definitions.ParentComponent;
import io.wispforest.owo.ui.definitions.Sizing;
import net.minecraft.client.gui.screen.Screen;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.Text;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;

/**
 * A specification of a UI hierarchy parsed from an
 * XML definition. You can use this to create a UI adapter for your
 * screen with {@link #createAdapter(Class, Screen)} as well as expanding
 * templates via {@link #expandTemplate(Class, String, Map)}
 */
public class OwoUISpec {

    private final Element componentsElement;
    private final Map<String, Element> templates;

    private final Deque<ExpansionFrame> expansionStack = new ArrayDeque<>();

    protected OwoUISpec(Element componentsElement, Map<String, Element> templates) {
        this.componentsElement = componentsElement;
        this.templates = templates;
    }

    protected OwoUISpec(Element docElement) {
        docElement.normalize();
        if (!docElement.getNodeName().equals("owo-ui")) {
            throw new UIParsingException("");
        }

        final var children = OwoUIParsing.childElements(docElement);
        if (!children.containsKey("components")) throw new UIParsingException("Missing 'components' element in UI specification");

        var componentsList = OwoUIParsing.<Element>allChildrenOfType(children.get("components"), Node.ELEMENT_NODE);
        if (componentsList.size() == 1) {
            this.componentsElement = componentsList.get(0);
        } else {
            throw new UIParsingException("Invalid number of children in 'components' element - a single child must be declared");
        }

        this.templates = OwoUIParsing.get(children, "templates", OwoUIParsing::childElements).orElse(Collections.emptyMap());
    }

    /**
     * Load the UI specification declared in the given file. If the file cannot
     * be found or an XML parsing error occurs, and empty specification is
     * returned and an error is logged
     *
     * @param path The file to read from
     * @return The parsed UI specification
     */
    public static @Nullable OwoUISpec load(Path path) {
        try (var in = Files.newInputStream(path)) {
            return load(in);
        } catch (Exception error) {
            Owo.LOGGER.warn("Could not load UI spec from file {}", path, error);
            return null;
        }
    }

    /**
     * Load the UI specification declared in the XML document
     * encoded by the given input stream. Contrary to {@link #load(Path)},
     * this method throws if a parsing error occurs
     *
     * @param stream The input stream to decode and read
     * @return The parsed UI specification
     */
    public static OwoUISpec load(InputStream stream) throws ParserConfigurationException, IOException, SAXException, UIParsingException {
        return new OwoUISpec(DocumentBuilderFactory.newDefaultInstance().newDocumentBuilder().parse(stream).getDocumentElement());
    }

    /**
     * Create a UI adapter which contains the component hierarchy
     * declared by this UI specification, attached to the given screen.
     * <p>
     * If there are components in your hierarchy you need to modify in
     * code after the main hierarchy has been parsed, give them an id
     * and look them up via {@link ParentComponent#childById(Class, String)}
     */
    public <T extends ParentComponent> OwoUIAdapter<T> createAdapter(Class<T> expectedRootComponentClass, Screen screen) {
        return OwoUIAdapter.create(screen, (horizontalSizing, verticalSizing) -> this.parseComponentTree(expectedRootComponentClass));
    }

    /**
     * Attempt to parse the given XMl element into a component,
     * expanding any templates encountered. If the XML does
     * not describe a valid component, a {@link UIParsingException}
     * may be thrown
     *
     * @param componentElement The XML element represented the
     *                         component to parse.
     * @return The parsed component
     */
    @SuppressWarnings("unchecked")
    public <T extends Component> T parseComponent(Class<T> expectedClass, Element componentElement) {
        if (componentElement.getNodeName().equals("template")) {
            var templateName = componentElement.getAttribute("name").strip();
            if (templateName.isEmpty()) {
                throw new UIParsingException("Template element is missing 'name' attribute");
            }

            var templateParams = new HashMap<String, String>();
            var childParams = new HashMap<String, Element>();
            for (var element : OwoUIParsing.<Element>allChildrenOfType(componentElement, Node.ELEMENT_NODE)) {
                if (element.getNodeName().equals("child")) {
                    childParams.put(
                            element.getAttribute("id"),
                            OwoUIParsing.<Element>allChildrenOfType(element, Node.ELEMENT_NODE).get(0)
                    );
                } else {
                    templateParams.put(element.getNodeName(), element.getTextContent());
                }
            }

            return this.expandTemplate(expectedClass, templateName, templateParams::get, childParams::get);
        }

        var component = OwoUIParsing.getFactory(componentElement).apply(componentElement);
        component.parseProperties(this, componentElement, OwoUIParsing.childElements(componentElement));

        if (!expectedClass.isAssignableFrom(component.getClass())) {
            var idString = componentElement.hasAttribute("id")
                    ? " with id '" + componentElement.getAttribute("id") + "'"
                    : "";

            throw new IncompatibleUISpecException(
                    "Expected component '" + componentElement.getNodeName() + "'"
                            + idString
                            + " to be a " + expectedClass.getSimpleName()
                            + ", but it is a " + component.getClass().getSimpleName()
            );
        }

        return (T) component;
    }

    /**
     * Expand a template into a component, applying
     * parameter mappings by invoking the given mapping
     * function and creating template children using the given
     * child supplier
     *
     * @param name              The name of the template to expand
     * @param parameterSupplier The parameter mapping function to invoke
     *                          for each parameter encountered in the template
     * @param childSupplier     The template child mapping function to invoke
     *                          for each template child the target template defines
     * @return The expanded template parsed into a component
     */
    @SuppressWarnings("unchecked")
    public <T extends Component> T expandTemplate(Class<T> expectedClass, String name, Function<String, String> parameterSupplier, Function<String, Element> childSupplier) {
        if (this.expansionStack.isEmpty()) {
            this.expansionStack.push(new ExpansionFrame(parameterSupplier, childSupplier));
        } else {
            final var currentFrame = this.expansionStack.peek();
            this.expansionStack.push(new ExpansionFrame(
                    this.cascadeIfNull(currentFrame.parameterSupplier, parameterSupplier),
                    this.cascadeIfNull(currentFrame.childSupplier, childSupplier)
            ));
        }

        var template = (Element) this.templates.get(name);
        if (template == null) {
            throw new UIParsingException("Unknown template '" + name + "'");
        } else {
            template = (Element) template.cloneNode(true);
        }

        this.expandChildren(template);
        this.applySubstitutions(template);

        final var component = this.parseComponent(Component.class, OwoUIParsing.<Element>allChildrenOfType(template, Node.ELEMENT_NODE).get(0));
        if (!expectedClass.isAssignableFrom(component.getClass())) {
            throw new IncompatibleUISpecException(
                    "Expected template '" + name + "'"
                            + " to expand into a " + expectedClass.getSimpleName()
                            + ", but it expanded into a " + component.getClass().getSimpleName()
            );
        }

        this.expansionStack.pop();
        return (T) component;
    }

    /**
     * Expand a template into a component, applying
     * the given parameter mappings. If the template defines child
     * elements, this method will most likely fail because
     * parameters for those can only be provided in XML
     *
     * @param name       The name of the template to expand
     * @param parameters The parameter mappings to apply while
     *                   expanding the template
     * @return The expanded template parsed into a component
     */
    public <T extends Component> T expandTemplate(Class<T> expectedClass, String name, Map<String, String> parameters) {
        return this.expandTemplate(expectedClass, name, parameters::get, s -> null);
    }

    protected <T extends ParentComponent> T parseComponentTree(Class<T> expectedRootComponentClass) {
        var documentComponent = this.parseComponent(expectedRootComponentClass, this.componentsElement);
        documentComponent.sizing(Sizing.fill(100), Sizing.fill(100));
        return documentComponent;
    }

    protected void applySubstitutions(Element template) {
        final var parameterSupplier = this.expansionStack.peek().parameterSupplier;

        for (var child : OwoUIParsing.<Element>allChildrenOfType(template, Node.ELEMENT_NODE)) {
            for (var node : OwoUIParsing.<Text>allChildrenOfType(child, Node.TEXT_NODE)) {
                var textContent = node.getTextContent();
                if (!textContent.matches("\\{\\{.*}}")) continue;

                final var substitution = parameterSupplier.apply(textContent.substring(2, textContent.length() - 2));
                if (substitution != null) {
                    node.setTextContent(substitution);
                }
            }
            applySubstitutions(child);
        }
    }

    protected void expandChildren(Element template) {
        final var childSupplier = this.expansionStack.peek().childSupplier;

        for (var child : OwoUIParsing.<Element>allChildrenOfType(template, Node.ELEMENT_NODE)) {
            if (child.getNodeName().equals("template-child")) {
                var childId = child.getAttribute("id");

                var expanded = childSupplier.apply(childId);
                if (expanded != null) {
                    expanded = (Element) expanded.cloneNode(true);
                    for (var element : OwoUIParsing.<Element>allChildrenOfType(child, Node.ELEMENT_NODE)) {
                        if (expanded.getElementsByTagName(element.getNodeName()).getLength() != 0) continue;
                        expanded.appendChild(element);
                    }

                    template.replaceChild(expanded, child);
                }
            }

            expandChildren(child);
        }
    }

    protected <T, S> Function<T, S> cascadeIfNull(Function<T, S> first, Function<T, S> second) {
        return t -> {
            var firstValue = first.apply(t);
            return firstValue == null ? second.apply(t) : firstValue;
        };
    }

    private record ExpansionFrame(Function<String, String> parameterSupplier, Function<String, Element> childSupplier) {}
}