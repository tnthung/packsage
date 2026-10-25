package dev.packsage.world.minecraft;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import dev.packsage.world.ServerWork;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/** Reads definitions only. Live trees and requirements are copied by {@link #snapshot()} on the server thread. */
public final class CommandReader<S> {
    public static final int PAGE = 64;
    public static final int USAGE_PREVIEW = 4;
    public static final int MAX_USAGE = 128;
    public static final int MAX_ARGUMENTS = 256;

    public record Summary(String name, boolean canUse, List<String> usage, String redirect) {
    }

    public record Argument(String path, String name, String type, List<String> examples, boolean canUse) {
    }

    public record Detail(String path, boolean canUse, List<String> usage, List<String> playerUsage, List<Argument> arguments,
                         boolean argumentsBounded, String redirect) {
    }

    private final CommandDispatcher<S> dispatcher;
    private final S source;
    private final Function<ArgumentType<?>, String> typeId;

    public CommandReader(CommandDispatcher<S> dispatcher, S source, Function<ArgumentType<?>, String> typeId) {
        this.dispatcher = dispatcher;
        this.source = source;
        this.typeId = typeId;
    }

    public List<Summary> search(String text) {
        var matches = new ArrayList<Summary>();
        for (var node : dispatcher.getRoot().getChildren()) {
            var usage = Arrays.asList(dispatcher.getAllUsage(node, source, false));
            if (text != null && !node.getName().toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT))
                    && usage.stream().noneMatch(line -> line.toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT)))) continue;
            matches.add(new Summary(node.getName(), node.canUse(source), List.copyOf(usage.subList(0, Math.min(USAGE_PREVIEW, usage.size()))),
                    redirectPath(node)));
        }
        matches.sort(Comparator.comparing(Summary::name));
        return List.copyOf(matches);
    }

    public Detail read(String path) {
        return read(path, 0);
    }

    /** Later argument pages walk the same detached tree, without following redirects or hiding forbidden commands. */
    public Detail read(String path, int argumentOffset) {
        path = path.strip();
        if (path.startsWith("/")) path = path.substring(1).strip();
        if (path.isEmpty()) return null;
        var parts = List.of(path.split(" +"));
        var node = dispatcher.findNode(parts);
        if (node == null) return null;
        path = String.join(" ", parts);
        boolean canUse = true;
        CommandNode<S> ancestor = dispatcher.getRoot();
        for (var part : parts) {
            ancestor = ancestor.getChild(part);
            canUse &= ancestor.canUse(source);
        }
        record Walk<S>(CommandNode<S> node, Iterator<CommandNode<S>> children, String path, boolean canUse) { }
        var stack = new ArrayDeque<Walk<S>>();
        var visited = Collections.newSetFromMap(new IdentityHashMap<CommandNode<S>, Boolean>());
        visited.add(node);
        stack.push(new Walk<>(node, node.getChildren().iterator(), path, canUse));
        var arguments = new ArrayList<Argument>();
        int skipped = 0;
        boolean bounded = false;
        while (!stack.isEmpty()) {
            var walk = stack.peek();
            if (!walk.children().hasNext()) {
                visited.remove(walk.node());
                stack.pop();
                continue;
            }
            var child = walk.children().next();
            if (!visited.add(child)) continue;
            var childPath = walk.path() + " " + child.getName();
            var allowed = walk.canUse() && child.canUse(source);
            Argument argument = null;
            if (child instanceof SnapshotNode<?> captured) {
                argument = captured.argument;
            } else if (child instanceof ArgumentCommandNode<?, ?> typed) {
                String type;
                try {
                    type = typeId.apply(typed.getType());
                } catch (RuntimeException exception) {
                    type = typed.getType().getClass().getSimpleName();
                }
                argument = new Argument("", child.getName(), type,
                        typed.getType().getExamples().stream().limit(4).toList(), allowed);
            }
            if (argument != null && skipped++ >= argumentOffset) {
                if (arguments.size() == MAX_ARGUMENTS) {
                    bounded = true;
                    break;
                }
                arguments.add(new Argument(childPath, argument.name(), argument.type(), argument.examples(), allowed));
            }
            stack.push(new Walk<>(child, child.getChildren().iterator(), childPath, allowed));
        }
        return new Detail(path, canUse, List.of(dispatcher.getAllUsage(node, source, false)),
                canUse ? List.copyOf(dispatcher.getSmartUsage(node, source).values()) : List.of(), List.copyOf(arguments), bounded,
                redirectPath(node));
    }

    private String redirectPath(CommandNode<S> node) {
        var redirect = node.getRedirect();
        if (redirect == null) return null;
        var path = dispatcher.getPath(redirect);
        return path.isEmpty() && redirect != dispatcher.getRoot() ? null : String.join(" ", path);
    }

    /**
     * Copies one node or redirect per step, including permission results and argument metadata. Vanilla/NeoForge
     * rebuild Commands on reload, but Brigadier exposes mutable child maps: never traverse the live tree on a worker.
     * Iterators remain on the server; a mod changing a map between ticks fails the read rather than publishing it.
     * A stopped slice retains its position and returns null until the copy is complete, so a cursor can resume it.
     */
    public ServerWork.Slice<CommandReader<S>> snapshot() {
        return new ServerWork.Slice<>() {
            record Walk<S>(CommandNode<S> parent, Iterator<CommandNode<S>> children) { }

            private final RootCommandNode<S> root = new RootCommandNode<>();
            private final Map<CommandNode<S>, CommandNode<S>> copies = new IdentityHashMap<>();
            private final ArrayDeque<Walk<S>> stack = new ArrayDeque<>();
            private Iterator<Map.Entry<CommandNode<S>, CommandNode<S>>> redirects;
            private CommandReader<S> result;

            {
                copies.put(dispatcher.getRoot(), root);
                stack.push(new Walk<>(dispatcher.getRoot(), dispatcher.getRoot().getChildren().iterator()));
            }

            @Override
            public boolean step() {
                if (!stack.isEmpty()) {
                    var walk = stack.peek();
                    if (!walk.children().hasNext()) {
                        stack.pop();
                        return true;
                    }
                    var node = walk.children().next();
                    var copy = copies.get(node);
                    if (copy == null) {
                        Argument argument = null;
                        if (node instanceof ArgumentCommandNode<?, ?> typed) {
                            List<String> examples;
                            try {
                                examples = typed.getType().getExamples().stream().limit(4).toList();
                            } catch (RuntimeException exception) {
                                examples = List.of();
                            }
                            String type;
                            try {
                                type = typeId.apply(typed.getType());
                            } catch (RuntimeException exception) {
                                type = typed.getType().getClass().getSimpleName();
                            }
                            argument = new Argument("", node.getName(), type, examples, false);
                        }
                        copy = new SnapshotNode<>(node.getName(), node.getUsageText(), node.getCommand() != null, node.canUse(source), argument);
                        copies.put(node, copy);
                        stack.push(new Walk<>(node, node.getChildren().iterator()));
                    }
                    copies.get(walk.parent()).addChild(copy);
                    return true;
                }
                if (redirects == null) redirects = copies.entrySet().iterator();
                if (redirects.hasNext()) {
                    var entry = redirects.next();
                    if (entry.getValue() instanceof SnapshotNode<S> copy && entry.getKey().getRedirect() != null) {
                        var target = entry.getKey().getRedirect();
                        copy.redirect = copies.get(target);
                        if (copy.redirect == null) copy.redirect = new SnapshotNode<>(target.getName(), target.getUsageText(), false, false, null);
                    }
                    return true;
                }
                result = new CommandReader<>(new CommandDispatcher<>(root), source, typeId);
                return false;
            }

            @Override
            public CommandReader<S> result() {
                return result;
            }

            @Override
            public boolean stopEarly() {
                return true;
            }
        };
    }

    /** Only Brigadier's usage readers see these nodes: no live requirements, callbacks, types or suggestions. */
    private static final class SnapshotNode<S> extends LiteralCommandNode<S> {
        private final String usage;
        private final Argument argument;
        private CommandNode<S> redirect;

        SnapshotNode(String name, String usage, boolean executable, boolean allowed, Argument argument) {
            super(name, executable ? context -> { throw new IllegalStateException("PackSage does not run commands"); } : null,
                    source -> allowed, null, null, false);
            this.usage = usage;
            this.argument = argument;
        }

        @Override
        public String getUsageText() {
            return usage;
        }

        @Override
        public CommandNode<S> getRedirect() {
            return redirect;
        }
    }
}
