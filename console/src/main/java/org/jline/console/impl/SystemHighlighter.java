/*
 * Copyright (c) 2002-2021, the original author or authors.
 *
 * This software is distributable under the BSD license. See the terms of the
 * BSD license in the documentation provided with this software.
 *
 * https://opensource.org/licenses/BSD-3-Clause
 */
package org.jline.console.impl;

import org.jline.builtins.Nano.SyntaxHighlighter;
import org.jline.builtins.Styles;
import org.jline.console.SystemRegistry;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import org.jline.reader.Parser;
import org.jline.reader.impl.DefaultHighlighter;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.OSUtils;
import org.jline.utils.StyleResolver;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Highlight command and language syntax using nanorc highlighter.
 *
 * @author <a href="mailto:matti.rintanikkola@gmail.com">Matti Rinta-Nikkola</a>
 */
public class SystemHighlighter extends DefaultHighlighter {
    private final static StyleResolver resolver = Styles.lsStyle();
    protected final SyntaxHighlighter commandHighlighter;
    protected final SyntaxHighlighter argsHighlighter;
    protected final SyntaxHighlighter langHighlighter;
    protected final SystemRegistry systemRegistry;
    protected final Set<String> fileHighlight = new HashSet<>();

    public SystemHighlighter(SyntaxHighlighter commandHighlighter, SyntaxHighlighter argsHighlighter
            , SyntaxHighlighter langHighlighter) {
        this.commandHighlighter = commandHighlighter;
        this.argsHighlighter = argsHighlighter;
        this.langHighlighter = langHighlighter;
        this.systemRegistry = SystemRegistry.get();
    }

    @Override
    public AttributedString highlight(LineReader reader, String buffer) {
        return doDefaultHighlight(reader) ? super.highlight(reader, buffer) : systemHighlight(reader, buffer);
    }

    public void addFileHighlight(String... commands) {
        fileHighlight.addAll(Arrays.asList(commands));
    }

    private boolean doDefaultHighlight(LineReader reader) {
        String search = reader.getSearchTerm();
        return ((search != null && search.length() > 0) || reader.getRegionActive() != LineReader.RegionType.NONE
                || errorIndex > -1 || errorPattern != null);
    }

    protected AttributedString systemHighlight(LineReader reader, String buffer) {
        AttributedString out;
        Parser parser = reader.getParser();
        String command = parser.getCommand(buffer.trim().split("\\s+")[0]);
        if (buffer.trim().isEmpty()) {
            out = new AttributedStringBuilder().append(buffer).toAttributedString();
        } else if (fileHighlight.contains(command)) {
            out = doFileHighlight(reader, buffer);
        } else if (systemRegistry.isCommandOrScript(command) || systemRegistry.isCommandAlias(command)) {
            out = doCommandHighlight(buffer);
        } else if (langHighlighter != null) {
            out = langHighlighter.highlight(buffer);
        } else {
            out = new AttributedStringBuilder().append(buffer).toAttributedString();
        }
        return out;
    }

    protected AttributedString doFileHighlight(LineReader reader, String buffer) {
        int idx = commandIndex(buffer);
        AttributedStringBuilder asb = new AttributedStringBuilder();
        if (idx < 0) {
            highlightCommand(buffer, asb);
        } else {
            highlightCommand(buffer.substring(0, idx), asb);
            ParsedLine parsedLine = reader.getParser().parse(buffer, buffer.length() + 1, Parser.ParseContext.COMPLETE);
            List<String> words = parsedLine.words();
            idx = words.get(0).length();
            for (int i = 1; i < words.size(); i++) {
                int nextIdx = buffer.substring(idx).indexOf(words.get(i)) + idx;
                for (int j = idx; j < nextIdx; j++) {
                    asb.append(buffer.charAt(j));
                }
                highlightFileArg(reader, words.get(i), asb);
                idx = nextIdx + words.get(i).length();
            }
        }
        return asb.toAttributedString();
    }

    protected AttributedString doCommandHighlight(String buffer) {
        AttributedString out;
        if (commandHighlighter != null || argsHighlighter != null) {
            int idx = commandIndex(buffer);
            AttributedStringBuilder asb = new AttributedStringBuilder();
            if (idx < 0) {
                highlightCommand(buffer, asb);
            } else {
                highlightCommand(buffer.substring(0, idx), asb);
                highlightArgs(buffer.substring(idx), asb);
            }
            out = asb.toAttributedString();
        } else {
            out = new AttributedStringBuilder().append(buffer).toAttributedString();
        }
        return out;
    }

    private int commandIndex(String buffer) {
        int idx = -1;
        boolean cmdFound = false;
        for (int i = 0; i < buffer.length(); i++) {
            char c = buffer.charAt(i);
            if (c != ' ') {
                cmdFound = true;
            } else if (cmdFound) {
                idx = i;
                break;
            }
        }
        return idx;
    }

    private void highlightFileArg(LineReader reader, String arg, AttributedStringBuilder asb) {
        if (arg.startsWith("-")) {
            highlightArgs(arg, asb);
        } else {
            String separator = reader.isSet(LineReader.Option.USE_FORWARD_SLASH)
                    ? "/" : Paths.get(System.getProperty("user.dir")).getFileSystem().getSeparator();
            StringBuilder sb = new StringBuilder();
            try {
                Path path = new File(arg).toPath();
                Iterator<Path> iterator = path.iterator();
                if (OSUtils.IS_WINDOWS && arg.matches("^[A-Za-z]:.*$")) {
                    if (arg.length() == 2) {
                        sb.append(arg);
                        asb.append(arg);
                    } else if (arg.charAt(2) == separator.charAt(0)) {
                        sb.append(arg.substring(0, 3));
                        asb.append(arg.substring(0, 3));
                    }
                }
                if (arg.startsWith(separator)) {
                    sb.append(separator);
                    asb.append(separator);
                }
                while (iterator.hasNext()) {
                    sb.append(iterator.next());
                    highlightFile(new File(sb.toString()).toPath(), asb);
                    if (iterator.hasNext()) {
                        sb.append(separator);
                        asb.append(separator);
                    }
                }
                if (arg.length() > 2 && !arg.matches("^[A-Za-z]:" + separator) && arg.endsWith(separator)) {
                    asb.append(separator);
                }
            } catch (Exception e) {
                asb.append(arg);
            }
        }
    }

    private void highlightFile(Path path, AttributedStringBuilder asb) {
        AttributedStringBuilder sb = new AttributedStringBuilder();
        String name = path.getFileName().toString();
        int idx = name.lastIndexOf(".");
        String type = idx != -1 ? ".*" + name.substring(idx): null;
        if (Files.isSymbolicLink(path)) {
            sb.styled(resolver.resolve(".ln"), name);
        } else if (Files.isDirectory(path)) {
            sb.styled(resolver.resolve(".di"), name);
        } else if (Files.isExecutable(path) && !OSUtils.IS_WINDOWS) {
            sb.styled(resolver.resolve(".ex"), name);
        } else if (type != null && resolver.resolve(type).getStyle() != 0) {
            sb.styled(resolver.resolve(type), name);
        } else if (Files.isRegularFile(path)) {
            sb.styled(resolver.resolve(".fi"), name);
        } else {
            sb.append(name);
        }
        asb.append(sb);
    }

    private void highlightArgs(String args, AttributedStringBuilder asb) {
        if (argsHighlighter != null) {
            asb.append(argsHighlighter.highlight(args));
        } else {
            asb.append(args);
        }
    }

    private void highlightCommand(String command, AttributedStringBuilder asb) {
        if (commandHighlighter != null) {
            asb.append(commandHighlighter.highlight(command));
        } else {
            asb.append(command);
        }
    }
}
