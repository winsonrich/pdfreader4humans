// This open source code is distributed without warranties according to the license published at http://www.apache.org/licenses/LICENSE-2.0
package edworld.pdfreader4humans;

import static edworld.pdfreader4humans.util.TextUtil.removeDiacritics;
import static java.awt.image.BufferedImage.TYPE_INT_ARGB;
import static java.lang.Math.abs;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.lang.Math.round;
import static java.util.Collections.sort;
import static java.util.regex.Matcher.quoteReplacement;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

import org.apache.commons.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

import edworld.pdfreader4humans.util.PDFUtil;

public class PDFReader {
	protected static final String HYPHEN = "-";
	protected static final String SPACE = " ";
	protected static final String UTF_8 = "UTF-8";
	protected static final String LINE_BREAK = System.getProperty("line.separator");
	protected URL url;
	protected float tolerance;
	protected List<List<Component>> firstLevel = new ArrayList<List<Component>>();
	protected Map<String, String> templateMap = new HashMap<String, String>();
	protected Component lastContainer;
	protected TextComponent lastComponent;

	/**
	 * Class responsible for reading PDF contents in the same order a human
	 * would read them.
	 * 
	 * @param url
	 *            the PDF's location
	 * @param componentLocator
	 *            an instance of a PDFComponentLocator subclass such as
	 *            MainPDFComponentLocator
	 * @param boxDetector
	 *            an instance of a BoxDetector subclass such as MainBoxDetector
	 * @param marginDetector
	 *            an instance of a MarginDetector subclass such as
	 *            MainMarginDetector
	 * @throws IOException
	 */
	public PDFReader(URL url, PDFComponentLocator componentLocator, BoxDetector boxDetector,
			MarginDetector marginDetector) throws IOException {
		this(url, componentLocator, boxDetector, marginDetector, 0);
	}

	/**
	 * Class responsible for reading PDF contents in the same order a human
	 * would read them.
	 * 
	 * @param url
	 *            the PDF's location
	 * @param componentLocator
	 *            an instance of a PDFComponentLocator subclass such as
	 *            MainPDFComponentLocator
	 * @param boxDetector
	 *            an instance of a BoxDetector subclass such as MainBoxDetector
	 * @param marginDetector
	 *            an instance of a MarginDetector subclass such as
	 *            MainMarginDetector
	 * @param tolerance
	 *            tolerance used to verify containment of components
	 * @throws IOException
	 */
	public PDFReader(URL url, PDFComponentLocator componentLocator, BoxDetector boxDetector,
			MarginDetector marginDetector, float tolerance) throws IOException {
		this.url = url;
		this.tolerance = tolerance;
		PDDocument doc = PDFUtil.load(url);
		try {
			readAllPages(doc, componentLocator, boxDetector, marginDetector);
		} finally {
			doc.close();
		}
	}

	public List<Component> getFirstLevelComponents(int pageNumber) {
		return firstLevel.get(pageNumber - 1);
	}

	public String toXML() {
		String output = template("pdfreader4humans.xml", 0);
		String content = "";
		for (int pageIndex = 0; pageIndex < firstLevel.size(); pageIndex++)
			content += pageToXML(pageIndex + 1, firstLevel.get(pageIndex), 1);
		return removeEmptyLines(output.replaceAll("\\$\\{content\\}", quoteReplacement(content)));
	}

	public List<String> toTextLines() {
		List<String> lines = new ArrayList<String>();
		for (int pageIndex = 0; pageIndex < firstLevel.size(); pageIndex++)
			lines.addAll(pageToTextLines(pageIndex + 1, firstLevel.get(pageIndex)));
		return lines;
	}

	protected List<String> pageToTextLines(int pageNumber, List<Component> pageFirstLevelComponents) {
		List<String> lines = new ArrayList<String>();
		lastContainer = null;
		lastComponent = null;
		for (Component component : pageFirstLevelComponents)
			addToTextLines(component, null, lines);
		return lines;
	}

	private void addToTextLines(Component component, Component container, List<String> lines) {
		if (component instanceof TextComponent)
			addText((TextComponent) component, container, lines);
		for (Component child : component.getChildren())
			addToTextLines(child, component, lines);
	}

	private void addText(TextComponent component, Component container, List<String> lines) {
		if (container == lastContainer && consecutiveText(lastComponent, component, container)) {
			String lastText = lines.get(lines.size() - 1);
			lines.set(lines.size() - 1, joinConsecutiveText(lastText, component.getText()));
		} else
			lines.add(component.getText());
		lastContainer = container;
		lastComponent = component;
	}

	private boolean consecutiveText(TextComponent component1, TextComponent component2, Component container) {
		if (component1 == null)
			return false;
		if (component1.consecutive(component2, true))
			return true;
		if (container == null || alignedToCenter(component1, component2, container))
			return false;
		int nextWordLength = min(5, (component2.getText() + SPACE).indexOf(SPACE)) + 1;
		float distanceBetweenLines = component2.getFromY() - component1.getToY();
		return component1.getToX() + nextWordLength * component1.getAverageCharacterWidth() > container.getToX()
				&& (alignedToRight(component1, component2, container)
						|| component2.getFromX() - component2.getAverageCharacterWidth() < component1.getFromX())
				&& component1.getToX() > component2.getFromX()
				&& component1.getToX() + nextWordLength * component1.getAverageCharacterWidth() > component2.getToX()
				&& distanceBetweenLines < 1.5 * max(component1.getHeight(), component2.getHeight());
	}

	private boolean alignedToCenter(TextComponent component1, TextComponent component2, Component container) {
		float leftMargin1 = component1.getFromX() - container.getFromX();
		float rightMargin1 = container.getToX() - component1.getToX();
		float leftMargin2 = component2.getFromX() - container.getFromX();
		float rightMargin2 = container.getToX() - component2.getToX();
		return abs(rightMargin1 - leftMargin1) < 1 && abs(rightMargin2 - leftMargin2) < 1
				&& rightMargin1 + leftMargin1 > component1.getAverageCharacterWidth()
				&& rightMargin2 + leftMargin2 > component2.getAverageCharacterWidth();
	}

	private boolean alignedToRight(TextComponent component1, TextComponent component2, Component container) {
		return component1.getToX() + component1.getAverageCharacterWidth() > container.getToX()
				&& component2.getToX() + component2.getAverageCharacterWidth() > container.getToX();
	}

	private String joinConsecutiveText(String text1, String text2) {
		if (text1.endsWith(HYPHEN)) {
			if ((text2.equalsIgnoreCase("se") || removeDiacritics(text2).matches("(?i)se[^a-z].*"))
					&& text1.matches("(?i).*[^s]" + Matcher.quoteReplacement(HYPHEN)))
				return text1 + text2;
			return text1.substring(0, text1.length() - 1) + text2;
		}
		return text1 + SPACE + text2;
	}

	protected String removeEmptyLines(String text) {
		return text.replaceAll(LINE_BREAK + LINE_BREAK, LINE_BREAK);
	}

	protected String pageToXML(int pageNumber, List<Component> pageFirstLevelComponents, int indentLevel) {
		String pageTemplate = template("pdfreader4humans-page.xml", indentLevel);
		String content = "";
		for (Component component : pageFirstLevelComponents)
			content += output(component, indentLevel + 1);
		return pageTemplate.replaceAll("\\$\\{pageNumber\\}", String.valueOf(pageNumber)).replaceAll("\\$\\{content\\}",
				quoteReplacement(content));
	}

	protected String output(Component component, int indentLevel) {
		String componentTemplate = template("pdfreader4humans-" + component.getType() + ".xml", indentLevel);
		String content = "";
		for (Component child : component.getChildren())
			content += output(child, indentLevel + 1);
		return component.output(componentTemplate).replaceAll("\\$\\{content\\}", quoteReplacement(content));
	}

	public BufferedImage createPageImage(int pageNumber, int scaling, Color inkColor, Color backgroundColor,
			boolean showStructure) throws IOException {
		Map<String, Font> fonts = new HashMap<String, Font>();
		PDRectangle cropBox = getPageCropBox(pageNumber);
		BufferedImage image = new BufferedImage(round(cropBox.getWidth() * scaling),
				round(cropBox.getHeight() * scaling), TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		graphics.setBackground(backgroundColor);
		graphics.clearRect(0, 0, image.getWidth(), image.getHeight());
		graphics.setColor(backgroundColor);
		graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
		graphics.setColor(inkColor);
		graphics.scale(scaling, scaling);
		for (Component component : getFirstLevelComponents(pageNumber))
			draw(component, graphics, inkColor, backgroundColor, showStructure, fonts);
		graphics.dispose();
		return image;
	}

	protected void readAllPages(PDDocument doc, PDFComponentLocator componentLocator, BoxDetector boxDetector,
			MarginDetector marginDetector) throws IOException {
		int index = 0;
		for (PDPage page : doc.getPages()) {
			firstLevel.add(readPage(new PDFPage(index, page, doc), componentLocator, boxDetector, marginDetector));
			index++;
		}
	}

	protected List<Component> readPage(PDFPage page, PDFComponentLocator componentLocator, BoxDetector boxDetector,
			MarginDetector marginDetector) throws IOException {
		List<Component> firstLevelComponents = new ArrayList<Component>();
		List<GridComponent> gridComponents = componentLocator.locateGridComponents(page);
		List<TextComponent> textComponents = componentLocator.locateTextComponents(page);
		List<BoxComponent> boxes = boxDetector.detectBoxes(gridComponents);
		List<Component> containers = new ArrayList<Component>();
		containers.addAll(gridComponents);
		containers.addAll(boxes);
		List<Component> groups = groupConnectedComponents(containers);
		containers.addAll(groups);
		firstLevelComponents.addAll(groups);
		addComponents(boxes, firstLevelComponents, groups);
		addComponents(gridComponents, firstLevelComponents, containers);
		List<MarginComponent> margins = marginDetector.detectMargins(group(textComponents, firstLevelComponents));
		containers.addAll(margins);
		firstLevelComponents.addAll(margins);
		addComponents(textComponents, firstLevelComponents, containers);
		sortRecursively(firstLevelComponents);
		expandMargins(firstLevelComponents);
		return firstLevelComponents;
	}

	private void expandMargins(List<Component> components) {
		for (Component margin : components)
			if (margin instanceof MarginComponent)
				if (expandedTowards(margin.nextUpperHorizontalComponent(margin.getToX(), margin.getFromX(), components),
						margin, components)
						|| expandedTowards(
								margin.nextLowerHorizontalComponent(margin.getToX(), margin.getFromX(), components),
								margin, components)) {
					expandMargins(components);
					break;
				}
	}

	private boolean expandedTowards(Component nearComponent, Component margin, List<Component> components) {
		if (nearComponent != null && abs(nearComponent.getFromX() - margin.getFromX()) < 1
				&& abs(nearComponent.getToX() - margin.getToX()) < 1) {
			components.remove(nearComponent);
			components.remove(margin);
			float fromX = min(margin.getFromX(), nearComponent.getFromX());
			float fromY = min(margin.getFromY(), nearComponent.getFromY());
			float toX = max(margin.getToX(), nearComponent.getToX());
			float toY = max(margin.getToY(), nearComponent.getToY());
			MarginComponent newMargin = new MarginComponent(fromX, fromY, toX, toY);
			newMargin.getChildren().addAll(margin.getChildren());
			newMargin.addChild(nearComponent);
			sort(newMargin.getChildren());
			components.add(newMargin);
			sort(components);
			return true;
		}
		return false;
	}

	protected void sortRecursively(List<Component> components) {
		if (components.size() > 0)
			Component.smartSort(components);
		for (Component component : components)
			sortRecursively(component.getChildren());
	}

	protected List<? extends Component> group(List<TextComponent> textComponents, List<Component> layoutComponents) {
		List<Component> list = new ArrayList<Component>();
		list.addAll(layoutComponents);
		for (Component component : textComponents)
			if (findContainer(component, layoutComponents) == null)
				list.add(component);
		return list;
	}

	protected List<Component> groupConnectedComponents(List<Component> components) {
		Map<Component, Integer> groupMap = new HashMap<Component, Integer>();
		int lastGroupIndex = buildGroupMap(components, groupMap);
		List<Component> groups = new ArrayList<Component>(lastGroupIndex);
		for (int groupIndex = 1; groupIndex <= lastGroupIndex; groupIndex++)
			createGroup(groupIndex, groupMap, groups);
		return groups;
	}

	protected void createGroup(int groupIndex, Map<Component, Integer> groupMap, List<Component> groups) {
		float fromX = Float.POSITIVE_INFINITY;
		float fromY = Float.POSITIVE_INFINITY;
		float toX = Float.NEGATIVE_INFINITY;
		float toY = Float.NEGATIVE_INFINITY;
		for (Component component : groupMap.keySet())
			if (groupMap.get(component) == groupIndex) {
				fromX = min(component.getFromX(), fromX);
				fromY = min(component.getFromY(), fromY);
				toX = max(component.getToX(), toX);
				toY = max(component.getToY(), toY);
			}
		if (fromX != Float.POSITIVE_INFINITY || fromY != Float.POSITIVE_INFINITY || toX != Float.NEGATIVE_INFINITY
				|| toY != Float.NEGATIVE_INFINITY)
			groups.add(new GroupComponent(fromX, fromY, toX, toY));
	}

	private int buildGroupMap(List<Component> components, Map<Component, Integer> groupMap) {
		int lastGroupIndex = 0;
		for (int i = 0; i < components.size(); i++)
			for (int j = i + 1; j < components.size(); j++) {
				Component component1 = components.get(i);
				Component component2 = components.get(j);
				if (component1.intersects(component2))
					lastGroupIndex = mapToSameGroupIndex(component1, component2, lastGroupIndex, groupMap);
			}
		return lastGroupIndex;
	}

	private int mapToSameGroupIndex(Component component1, Component component2, int lastGroupIndex,
			Map<Component, Integer> groupMap) {
		Integer groupIndex1 = groupMap.get(component1);
		Integer groupIndex2 = groupMap.get(component2);
		if (groupIndex1 == null && groupIndex2 == null) {
			lastGroupIndex++;
			groupMap.put(component1, lastGroupIndex);
			groupMap.put(component2, lastGroupIndex);
		} else if (groupIndex1 != null && groupIndex2 == null) {
			groupMap.put(component2, groupIndex1);
		} else if (groupIndex1 == null && groupIndex2 != null) {
			groupMap.put(component1, groupIndex2);
		} else if (groupIndex1 != groupIndex2) {
			joinGroups(groupIndex1, groupIndex2, groupMap);
		}
		return lastGroupIndex;
	}

	protected void joinGroups(Integer groupIndex1, Integer groupIndex2, Map<Component, Integer> groupMap) {
		for (Component component : groupMap.keySet())
			if (groupMap.get(component) == groupIndex2)
				groupMap.put(component, groupIndex1);
	}

	protected void addComponents(List<? extends Component> components, List<Component> targetList,
			List<? extends Component> containers) {
		for (Component component : components) {
			Component container = findContainer(component, containers);
			if (container != null)
				container.addChild(component);
			else
				targetList.add(component);
		}
	}

	protected Component findContainer(Component component, List<? extends Component> containers) {
		Component container = null;
		float area = Float.POSITIVE_INFINITY;
		for (Component possibleContainer : containers)
			if (possibleContainer.contains(component, tolerance) && possibleContainer.getArea() < area) {
				container = possibleContainer;
				area = possibleContainer.getArea();
			}
		return container;
	}

	protected String template(String templateFileName, int indentLevel) {
		String template = templateMap.get(templateFileName);
		if (template != null)
			return indent(template, indentLevel);
		InputStream input = getClass().getResourceAsStream("/templates/" + templateFileName);
		try {
			try {
				template = IOUtils.toString(input, UTF_8);
				templateMap.put(templateFileName, template);
				return indent(template, indentLevel);
			} finally {
				input.close();
			}
		} catch (IOException e) {
			throw new IllegalArgumentException(e);
		}
	}

	protected String indent(String output, int indentLevel) {
		try {
			char[] tabs = new char[indentLevel];
			Arrays.fill(tabs, '\t');
			StringBuilder buffer = new StringBuilder();
			for (String line : IOUtils.readLines(new StringReader(output)))
				if (line.equals("${content}"))
					buffer.append(line);
				else
					buffer.append(tabs).append(line).append(LINE_BREAK);
			return buffer.toString();
		} catch (IOException e) {
			throw new IllegalArgumentException(e);
		}
	}

	private void draw(Component component, Graphics2D graphics, Color inkColor, Color backgroundColor,
			boolean showStructure, Map<String, Font> fonts) {
		for (Component child : component.getChildren())
			draw(child, graphics, inkColor, backgroundColor, showStructure, fonts);
		if (component instanceof BoxComponent && showStructure) {
			graphics.setColor(boxColor(backgroundColor));
			graphics.drawRect((int) component.getFromX(), (int) component.getFromY(), (int) component.getWidth(),
					(int) component.getHeight());
			graphics.setColor(inkColor);
		} else if (component instanceof GroupComponent && showStructure) {
			graphics.setColor(groupColor(backgroundColor));
			graphics.drawRect(round(component.getFromX()), round(component.getFromY()), round(component.getWidth()),
					round(component.getHeight()));
			graphics.setColor(inkColor);
		} else if (component instanceof MarginComponent && showStructure) {
			graphics.setColor(marginColor(backgroundColor));
			graphics.drawRect(round(component.getFromX()), round(component.getFromY()), round(component.getWidth()),
					round(component.getHeight()));
			graphics.setColor(inkColor);
		} else if (component.getType().equals("line"))
			graphics.drawLine(round(component.getFromX()), round(component.getFromY()), round(component.getToX()),
					round(component.getToY()));
		else if (component.getType().equals("rect"))
			graphics.drawRect(round(component.getFromX()), round(component.getFromY()), round(component.getWidth()),
					round(component.getHeight()));
		else if (component instanceof TextComponent) {
			graphics.setFont(font((TextComponent) component, fonts));
			graphics.drawString(((TextComponent) component).getText(), component.getFromX(), component.getToY());
		}
	}

	private Color boxColor(Color backgroundColor) {
		return new Color(Color.GRAY.getRGB() ^ backgroundColor.getRGB());
	}

	private Color groupColor(Color inkColor) {
		return new Color(Color.CYAN.getRGB() ^ inkColor.getRGB());
	}

	private Color marginColor(Color inkColor) {
		return new Color(Color.YELLOW.getRGB() ^ inkColor.getRGB());
	}

	private Font font(TextComponent component, Map<String, Font> fonts) {
		String key = component.getFontName() + ":" + component.getFontSize();
		Font font = fonts.get(key);
		if (font == null) {
			String name = component.getFontName().contains("Times") ? "TimesRoman" : "Dialog";
			int style = component.getFontName().contains("Bold") ? Font.BOLD
					: (component.getFontName().contains("Italic") ? Font.ITALIC : Font.PLAIN);
			font = new Font(name, style, (int) component.getFontSize());
			fonts.put(key, font);
		}
		return font;
	}

	private PDRectangle getPageCropBox(int pageIndex) throws IOException {
		PDDocument doc = PDFUtil.load(url);
		try {
			return doc.getPages().get(pageIndex - 1).getCropBox();
		} finally {
			doc.close();
		}
	}
}
