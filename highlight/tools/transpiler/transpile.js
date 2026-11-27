'use strict';

/**
 * Transpiles highlight.js (v10) language definitions into Java classes.
 *
 * A highlight.js language is a function `function(hljs) { ...; return {...}; }`
 * written in a small subset of JavaScript. This tool translates that subset
 * into an equivalent Java class implementing `org.scrivo.highlight.LanguageDefinition`.
 *
 * Java is statically typed, so the transpiler infers the type of each value:
 *
 * - object literals are modes (`new Mode().begin(...)...`), unless they are
 *   used as keyword maps (`map("keyword", "...")`) or hold other data;
 * - arrays are `List<Mode>` or `List<String>` (`list(...)`), shared by
 *   reference like JavaScript arrays;
 * - regular expressions are strings holding the JS regex source; the Java
 *   engine translates JS regex syntax when compiling;
 * - functions declared in the definition become private static methods, the
 *   local variables they use are passed as extra arguments.
 *
 * Anything outside the supported subset throws, so that new constructs in
 * future highlight.js versions are noticed instead of silently mistranslated.
 * The Java compiler and the comparison of the evaluated definitions
 * (verify.sh) check the result.
 */

const acorn = require('acorn');

const INDENT = '\t';
const TAB_WIDTH = 4;
const MAX_LINE = 120;
/** Marks the start of a trailing line comment, so suffixes can be inserted before it. */
const COMMENT_MARK = '\u0000';
/** Marks a line that only holds a comment. */
const COMMENT_LINE = '\u0001';
/** javac folds constant strings, which may not exceed 65535 bytes. */
const MAX_CONSTANT = 20000;

const JAVA_RESERVED = new Set(('abstract assert boolean break byte case catch char class const continue default do ' +
    'double else enum extends final finally float for goto if implements import instanceof int interface long native ' +
    'new package private protected public return short static strictfp super switch synchronized this throw throws ' +
    'transient try void volatile while true false null var record yield sealed permits _').split(' '));

/** Names that may not be used for language classes or helper methods. */
const RESERVED_NAMES = new Set(['Mode', 'Hljs', 'LanguageDefinition', 'Language', 'List', 'Map', 'Registry', 'Js',
    'String', 'Object', 'Integer', 'Boolean', 'define', 'Regex', 'Predicate']);

/** The functions of highlight.js' lib/regex.js, provided by org.scrivo.highlight.Regex. */
const REGEX_FUNCTIONS = ['concat', 'lookahead', 'anyNumberOfTimes', 'optional', 'either'];

/** The functions of the hljs object that create modes. */
const HLJS_MODE_FUNCTIONS = ['COMMENT', 'inherit', 'SHEBANG', 'END_SAME_AS_BEGIN'];

/** Mode properties and their types. */
const MODE_PROPS = {
    name: 'string',
    aliases: 'strings',
    case_insensitive: 'boolean',
    disableAutodetect: 'boolean',
    className: 'string',
    begin: 'string',
    end: 'string',
    beginKeywords: 'string',
    endsWithParent: 'boolean',
    endsParent: 'boolean',
    endSameAsBegin: 'boolean',
    lexemes: 'string',
    keywords: 'keywords',
    illegal: 'string',
    excludeBegin: 'boolean',
    excludeEnd: 'boolean',
    returnBegin: 'boolean',
    returnEnd: 'boolean',
    contains: 'modes',
    starts: 'mode',
    variants: 'modes',
    relevance: 'number',
    subLanguage: 'sublanguage',
    skip: 'boolean',
    'on:begin': 'callback',
    'on:end': 'callback',
    supersetOf: 'string',
    classNameAliases: 'map',
    match: 'string',
    compilerExtensions: 'extensions',
};

/** Keys that only occur in modes. */
const MODE_KEYS = ['begin', 'end', 'className', 'contains', 'variants', 'beginKeywords', 'keywords', 'starts',
    'relevance', 'illegal', 'endsWithParent', 'returnBegin', 'excludeBegin', 'excludeEnd', 'returnEnd', 'subLanguage',
    'endsParent', 'skip', 'lexemes', 'case_insensitive', 'aliases', 'name'];

const JAVA_TYPES = {
    string: 'String',
    number: 'Integer',
    boolean: 'Boolean',
    mode: 'Mode',
    modes: 'List<Mode>',
    strings: 'List<String>',
    map: 'Map<String, String>',
    objmap: 'Map<String, Object>',
    lang: 'Language',
    predicate: 'Predicate<String>',
    match: 'Match',
    response: 'Response',
    callback: 'Mode.Callback',
    modelists: 'List<List<Mode>>',
    stringset: 'Set<String>',
    values: 'List<Object>',
    extension: 'Mode.CompilerExtension',
    any: 'Object',
};

/** Types of JSDoc annotations, which tell the types of function parameters. */
const JSDOC_TYPES = {
    RegExpMatchArray: 'match',
    CallbackResponse: 'response',
    number: 'number',
    string: 'string',
    boolean: 'boolean',
    Mode: 'mode',
    'Mode[]': 'modes',
    'string[]': 'strings',
};

/** Setters of mode properties whose names are not Java identifiers. */
const MODE_SETTERS = { 'on:begin': 'onBegin', 'on:end': 'onEnd' };

class TranspileError extends Error {
    constructor(message, node) {
        super(message + (node && node.loc ? ' (line ' + node.loc.start.line + ')' : ''));
    }
}

/**
 * @param {string} languageId
 * @returns {string}
 */
function className(languageId) {
    const special = { '1c': 'OneC' };
    if (special[languageId]) {
        return special[languageId];
    }
    let name = languageId.split(/[^a-zA-Z0-9]+/).filter(Boolean)
        .map(function (p) { return p[0].toUpperCase() + p.slice(1); }).join('');
    if (/^[0-9]/.test(name) || JAVA_RESERVED.has(name.toLowerCase()) || RESERVED_NAMES.has(name)) {
        name = 'Lang' + name;
    }

    return name;
}

function javaName(name) {
    if (JAVA_RESERVED.has(name)) {
        return name + '_';
    }

    return name;
}

function javaType(type, node) {
    if (!JAVA_TYPES[type]) {
        throw new TranspileError('Cannot determine the Java type (' + type + ')', node);
    }

    return JAVA_TYPES[type];
}

// ---------------------------------------------------------------------------
// Java literal helpers

function javaString(value) {
    let out = '"';
    for (const ch of value) {
        const code = ch.codePointAt(0);
        if (ch === '\\') out += '\\\\';
        else if (ch === '"') out += '\\"';
        else if (ch === '\n') out += '\\n';
        else if (ch === '\r') out += '\\r';
        else if (ch === '\t') out += '\\t';
        else if (ch === '\b') out += '\\b';
        else if (ch === '\f') out += '\\f';
        else if (code < 0x20 || code === 0x7f || code === 0x2028 || code === 0x2029) {
            out += '\\u' + code.toString(16).padStart(4, '0');
        } else out += ch;
    }

    return out + '"';
}

/** Java processes unicode escapes everywhere, also in comments. */
function safeComment(text) {
    return text.replace(/\\u/g, '\\\\u');
}

// ---------------------------------------------------------------------------
// Comments

class Comments {
    constructor(comments, source) {
        this.comments = comments;
        this.source = source;
        this.used = new Set();
        this.lineStarts = [0];
        for (let i = 0; i < source.length; i++) {
            if (source.charCodeAt(i) === 10) this.lineStarts.push(i + 1);
        }
    }

    /** A trailing comment, to be appended to a line of code. */
    format(c) {
        if (c.type === 'Line') {
            return COMMENT_MARK + '//' + safeComment(c.value.replace(/\s+$/, ''));
        }

        return '/*' + safeComment(c.value).replace(/\s*\n\s*/g, ' ') + '*/';
    }

    /** Comments in [from, to) not yet used. */
    take(from, to) {
        const out = [];
        for (const c of this.comments) {
            if (c.start >= from && c.end <= to && !this.used.has(c)) {
                this.used.add(c);
                out.push(c);
            }
        }

        return out;
    }

    hasUnused(from, to) {
        return this.comments.some((c) => !this.used.has(c) && c.start > from && c.end < to);
    }

    lineOf(pos) {
        let lo = 0;
        let hi = this.lineStarts.length - 1;
        while (lo < hi) {
            const mid = (lo + hi + 1) >> 1;
            if (this.lineStarts[mid] <= pos) lo = mid;
            else hi = mid - 1;
        }

        return lo + 1;
    }

    /** Comment lines, indented with pad. */
    lines(c, pad) {
        if (c.type === 'Line') return [COMMENT_LINE + pad + '//' + safeComment(c.value.replace(/\s+$/, ''))];
        const body = safeComment(c.value).split('\n');

        return body.map(function (l, i) {
            const t = i === 0 ? '/*' + l : ' ' + l.replace(/^\s*/, '');

            return (pad + t).trimEnd();
        }).map(function (l, i, all) {
            if (i < all.length - 1) return COMMENT_LINE + l;

            // the end of the comment, aligned with the lines before it
            return COMMENT_LINE + (l.trim() === '' && i > 0 ? pad + ' */' : l + '*/');
        });
    }
}

// ---------------------------------------------------------------------------
// Scope / usage analysis

function propKey(prop) {
    if (prop.computed) {
        throw new TranspileError('Computed property keys are not supported', prop);
    }
    if (prop.key.type === 'Identifier') return prop.key.name;
    if (prop.key.type === 'Literal') return String(prop.key.value);
    throw new TranspileError('Unsupported property key ' + prop.key.type, prop);
}

function rootName(node) {
    while (node.type === 'MemberExpression') {
        node = node.object;
    }

    return node.type === 'Identifier' ? node.name : null;
}

/**
 * Collects facts about the variables declared in a function body that tell
 * their types.
 */
function analyse(fnBody, params) {
    const info = {
        vars: new Map(),         // name -> {init, kind, declIndex}
        assignments: new Map(),  // name -> [value]
        pushed: new Map(),       // name -> [value]
        modeUse: new Set(),      // variables used where a mode (or a list of modes) is expected
        keywordUse: new Set(),   // object variables used as keyword maps
        stringListUse: new Set(),// arrays used as lists of strings
        functions: new Map(),    // name -> FunctionDeclaration / FunctionExpression
        calls: new Map(),        // function name -> [CallExpression]
    };

    params.forEach(function (p) {
        info.vars.set(p, { init: null, kind: 'param', declIndex: -1 });
    });

    fnBody.body.forEach(function (stmt, index) {
        if (stmt.type === 'VariableDeclaration') {
            stmt.declarations.forEach(function (d) {
                info.vars.set(d.id.name, { init: d.init, kind: 'var', declIndex: index });
                if (d.init && isFunction(d.init)) {
                    info.functions.set(d.id.name, d.init);
                }
            });
        } else if (stmt.type === 'FunctionDeclaration') {
            info.vars.set(stmt.id.name, { init: stmt, kind: 'function', declIndex: index });
            info.functions.set(stmt.id.name, stmt);
        }
    });

    function add(map, key, value) {
        if (!map.has(key)) map.set(key, []);
        map.get(key).push(value);
    }

    function markModeUse(node) {
        if (node && node.type === 'Identifier') {
            info.modeUse.add(node.name);
        }
        if (node && node.type === 'ArrayExpression') {
            node.elements.forEach(markModeUse);
        }
    }

    function visit(node) {
        if (!node || typeof node.type !== 'string') {
            return;
        }
        if (node !== fnBody && (node.type === 'FunctionExpression' || node.type === 'FunctionDeclaration')) {
            // nested functions have their own scope, but they may use ours
        }

        if (node.type === 'Property') {
            const k = propKey(node);
            if (k === 'keywords' && node.value.type === 'Identifier') {
                info.keywordUse.add(node.value.name);
            } else if (MODE_PROPS[k] === 'modes' || MODE_PROPS[k] === 'mode') {
                markModeUse(node.value);
            } else if (MODE_PROPS[k] === 'strings' && node.value.type === 'Identifier') {
                info.stringListUse.add(node.value.name);
            }
        }

        if (node.type === 'ReturnStatement') {
            markModeUse(node.argument);
        }

        if (node.type === 'CallExpression') {
            const callee = node.callee;
            if (callee.type === 'Identifier') {
                add(info.calls, callee.name, node);
            }
            if (callee.type === 'MemberExpression') {
                const method = callee.property.name;
                if (['push', 'unshift'].includes(method) && callee.object.type === 'Identifier') {
                    node.arguments.forEach(function (a) { add(info.pushed, callee.object.name, a); });
                }
                if (callee.object.type === 'Identifier' && callee.object.name === 'hljs' &&
                    ['inherit', 'END_SAME_AS_BEGIN', 'SHEBANG'].includes(method)) {
                    node.arguments.forEach(markModeUse);
                }
                if (callee.object.type === 'Identifier' && callee.object.name === 'Object' && method === 'assign') {
                    markModeUse(node.arguments[0]);
                }
                if (method === 'join' && callee.object.type === 'Identifier') {
                    info.stringListUse.add(callee.object.name);
                }
            }
        }

        if (node.type === 'AssignmentExpression') {
            if (node.left.type === 'Identifier') {
                add(info.assignments, node.left.name, node.right);
            } else if (node.left.type === 'MemberExpression') {
                const name = rootName(node.left);
                if (name && (node.left.object.type === 'MemberExpression' || node.left.computed)) {
                    // contains[0].contains = ...  -> array element is a mode
                    info.modeUse.add(name);
                }
                if (!node.left.computed && (MODE_PROPS[node.left.property.name] === 'modes' ||
                    MODE_PROPS[node.left.property.name] === 'mode')) {
                    markModeUse(node.right);
                    if (node.left.object.type === 'Identifier') {
                        info.modeUse.add(node.left.object.name);
                    }
                }
            }
        }

        for (const k of Object.keys(node)) {
            if (k === 'loc' || k === 'start' || k === 'end') continue;
            const child = node[k];
            if (Array.isArray(child)) {
                child.forEach(visit);
            } else if (child && typeof child.type === 'string') {
                visit(child);
            }
        }
    }

    visit(fnBody);

    return info;
}

/** The first return statement of a block, not in nested functions. */
function findReturn(node) {
    if (!node || typeof node.type !== 'string') return null;
    if (node.type === 'ReturnStatement') return node;
    if (isFunction(node) || node.type === 'FunctionDeclaration') return null;
    for (const k of Object.keys(node)) {
        if (k === 'loc') continue;
        const child = node[k];
        const found = Array.isArray(child) ? child.map(findReturn).find(Boolean) : findReturn(child);
        if (found) return found;
    }

    return null;
}

function isFunction(node) {
    return node.type === 'FunctionExpression' || node.type === 'ArrowFunctionExpression';
}

/** The name of a parameter, which may have a default value. */
function paramName(p) {
    if (p.type === 'Identifier') return p.name;
    if (p.type === 'AssignmentPattern' && p.left.type === 'Identifier') return p.left.name;
    throw new TranspileError('Unsupported parameter', p);
}

/** The names a parameter binds: its name, or the names of a destructured object (`{ after }`). */
function paramNames(p) {
    if (p.type === 'AssignmentPattern' && p.left.type === 'ObjectPattern') return paramNames(p.left);
    if (p.type === 'ObjectPattern') {
        return p.properties.map(function (prop) {
            if (prop.type !== 'Property' || !prop.shorthand || prop.value.type !== 'Identifier') {
                throw new TranspileError('Unsupported destructuring', prop);
            }

            return prop.value.name;
        });
    }

    return [paramName(p)];
}

/**
 * The types of the parameters of a function from its JSDoc comment, like
 * `@param {RegExpMatchArray} match` or `@param {{after:number}} param1`.
 *
 * @returns {Object<string, string|Object<string, string>>} by parameter name, or by position for destructured ones
 */
function jsdocParamTypes(source, comments, fn) {
    // the comment right before the function, its declaration or its property
    const comment = comments.filter(function (c) {
        return c.type === 'Block' && c.end <= fn.start &&
            /^\s*((const|let|var)\s+[\w$]+\s*=|[\w$'"]+\s*:|function\b[^(]*)?\s*(async\s*)?$/.test(source.slice(c.end, fn.start));
    }).pop();
    const types = {};
    if (!comment) return types;
    const re = /@param\s+\{(\{[^}]*\}|[^}]+)\}\s+\[?([\w$]+)/g;
    let m;
    let position = 0;
    while ((m = re.exec(comment.value)) !== null) {
        const t = m[1].trim();
        if (t.startsWith('{')) {
            const members = {};
            t.slice(1, -1).split(',').forEach(function (member) {
                const kv = member.split(':').map(function (x) { return x.trim(); });
                if (kv.length === 2 && JSDOC_TYPES[kv[1]]) members[kv[0]] = JSDOC_TYPES[kv[1]];
            });
            types['#' + position] = members;
        } else if (JSDOC_TYPES[t]) {
            types[m[2]] = JSDOC_TYPES[t];
        }
        position++;
    }

    return types;
}

/** Free identifiers referenced inside a function (excluding its params and locals). */
function freeVariables(fn) {
    const bound = new Set([].concat(...fn.params.map(paramNames)));
    const free = new Set();

    (fn.body.type === 'BlockStatement' ? fn.body.body : []).forEach(function (node) {
        if (node.type === 'VariableDeclaration') {
            node.declarations.forEach(function (d) { bound.add(d.id.name); });
        }
        if (node.type === 'FunctionDeclaration') {
            bound.add(node.id.name);
        }
    });

    function visit(node, parent, key) {
        if (!node || typeof node.type !== 'string') return;
        if (node.type === 'Identifier') {
            const isProp = parent && parent.type === 'MemberExpression' && key === 'property' && !parent.computed;
            const isKey = parent && parent.type === 'Property' && key === 'key';
            if (!isProp && !isKey && !bound.has(node.name)) free.add(node.name);
            return;
        }
        if ((node.type === 'FunctionExpression' || node.type === 'ArrowFunctionExpression' ||
            node.type === 'FunctionDeclaration') && node !== fn) {
            freeVariables(node).forEach(function (v) {
                if (!bound.has(v)) free.add(v);
            });
            return;
        }
        for (const k of Object.keys(node)) {
            if (k === 'loc') continue;
            const child = node[k];
            if (Array.isArray(child)) child.forEach(function (c) { visit(c, node, k); });
            else if (child && typeof child.type === 'string') visit(child, node, k);
        }
    }
    visit(fn.body, null, null);

    return free;
}

// ---------------------------------------------------------------------------
// Emitter

class Emitter {
    constructor(source, comments, languageId) {
        this.source = source;
        this.comments = comments;
        this.languageId = languageId;
        this.scopes = [];
        this.methods = [];      // emitted helper methods
        this.methodInfo = new Map(); // function node -> {name, params, captured, returnType}
        this.imports = new Set();
        this.imports_ = new Map(); // imported name -> {kind: 'regex' | 'module' | 'language', ...}
        this.typing = new Set(); // guards against infinite recursion while typing
    }

    get scope() {
        return this.scopes[this.scopes.length - 1];
    }

    lookup(name) {
        for (let i = this.scopes.length - 1; i >= 0; i--) {
            if (this.scopes[i].info.vars.has(name)) {
                return { scope: this.scopes[i], decl: this.scopes[i].info.vars.get(name) };
            }
        }

        return null;
    }

    use(name) {
        this.imports.add(name);
    }

    // ---- typing ---------------------------------------------------------

    /**
     * @returns {'string'|'number'|'boolean'|'null'|'mode'|'modes'|'strings'|'map'|'objmap'|'lang'|'hljs'|'function'|'self'|'unknown'}
     */
    typeOf(node) {
        switch (node.type) {
            case 'Literal':
                if (node.regex) return 'string';
                if (node.value === null) return 'null';

                return typeof node.value;
            case 'TemplateLiteral':
                return 'string';
            case 'UnaryExpression':
                if (node.operator === '!') return 'boolean';
                if (node.operator === '-') return 'number';
                if (node.operator === 'typeof') return 'string';

                return 'unknown';
            case 'ConditionalExpression': {
                const t = this.typeOf(node.consequent);

                return t === 'unknown' || t === 'null' ? this.typeOf(node.alternate) : t;
            }
            case 'ArrayExpression':
                return this.arrayLiteralType(node);
            case 'ObjectExpression':
                return 'mode';
            case 'BinaryExpression':
                if (node.operator === '+') {
                    const l = this.typeOf(node.left);
                    const r = this.typeOf(node.right);

                    return (l === 'string' || r === 'string') ? 'string' : (l === 'number' && r === 'number' ? 'number' : 'unknown');
                }
                if (['===', '!==', '==', '!=', '<', '>', '<=', '>='].includes(node.operator)) return 'boolean';
                if (['-', '*'].includes(node.operator)) return 'number';

                return 'unknown';
            case 'NewExpression':
                if (node.callee.type === 'Identifier' && node.callee.name === 'RegExp') return 'string';
                if (node.callee.type === 'Identifier' && node.callee.name === 'Set') return 'stringset';

                return 'unknown';
            case 'LogicalExpression': {
                const l = this.typeOf(node.left);

                return l === 'unknown' || l === 'null' ? this.typeOf(node.right) : l;
            }
            case 'Identifier':
                return this.identifierType(node.name, node);
            case 'MemberExpression':
                return this.memberType(node);
            case 'CallExpression':
                return this.callType(node);
            case 'FunctionExpression':
                return node.params.length === 2 ? 'callback' : 'function';
            case 'ArrowFunctionExpression':
                // a callback that tests strings, like the argument of filter
                if (node.body.type !== 'BlockStatement' && node.params.length === 1) return 'predicate';
                // the callback of a mode, (match, response) => ...
                if (node.params.length === 2) return 'callback';

                return 'function';
            default:
                return 'unknown';
        }
    }

    arrayLiteralType(node) {
        let sawString = false;
        // a list of strings and regex literals keeps the regexes as values (JavaScript code may tell them apart)
        const regexes = node.elements.some(function (el) { return el && el.type === 'Literal' && el.regex; });
        const plain = node.elements.some(function (el) { return el && el.type === 'Literal' && typeof el.value === 'string'; });
        if (regexes && plain) return 'values';
        for (const el of node.elements) {
            if (el && el.type === 'SpreadElement') {
                const t = this.typeOf(el.argument);
                if (t === 'modes' || t === 'strings' || t === 'values') return t;
                continue;
            }
            if (el && el.type === 'Literal' && el.value === 'self') {
                // 'self' in a list of modes, unless the list holds other strings
                const others = node.elements.some((o) => o && o !== el && o.type !== 'SpreadElement' && this.typeOf(o) === 'string');
                if (!others) return 'modes';
            }
            const t = el ? this.typeOf(el) : 'unknown';
            if (t === 'mode') return 'modes';
            if (t === 'string') sawString = true;
        }

        return sawString ? 'strings' : (node.elements.length ? 'unknown' : 'empty');
    }

    identifierType(name, node) {
        if (name === 'hljs') return 'hljs';
        if (name === 'undefined') return 'null';
        if (this.imports_.has(name) && !this.lookup(name)) {
            const imp = this.imports_.get(name);

            return imp.kind === 'binding' ? imp.type : 'import';
        }
        const found = this.lookup(name);
        if (!found) return 'unknown';
        const d = found.decl;
        if (d.kind === 'function') return 'function';
        if (d.kind === 'param') return d.type || 'unknown';
        if (d.type) return d.type;
        const key = found.scope.id + ':' + name;
        if (this.typing.has(key)) return 'unknown';
        this.typing.add(key);
        try {
            d.type = this.variableType(name, d, found.scope);
        } finally {
            this.typing.delete(key);
        }

        return d.type;
    }

    variableType(name, d, scope) {
        const info = scope.info;
        const init = d.init;
        const assigned = info.assignments.get(name) || [];
        if (init && isFunction(init)) return 'function';
        if (init && init.type === 'ObjectExpression') return this.objectVarType(name, init, scope);
        if (init && init.type === 'ArrayExpression') {
            const t = this.withScope(scope, () => this.arrayLiteralType(init));
            if (t === 'modes' || t === 'strings' || t === 'values') return t;

            return this.withScope(scope, () => this.listTypeFromUse(name, info));
        }
        const candidates = (init ? [init] : []).concat(assigned);
        for (const c of candidates) {
            const t = this.withScope(scope, () => this.typeOf(c));
            if (t === 'empty') return this.withScope(scope, () => this.listTypeFromUse(name, info));
            if (t !== 'unknown' && t !== 'null') return t;
        }
        if (info.modeUse.has(name)) return 'mode';

        return 'unknown';
    }

    /** The type of an array variable, from the way it is used. */
    listTypeFromUse(name, info) {
        for (const v of info.pushed.get(name) || []) {
            const t = this.typeOf(v);
            if (t === 'mode') return 'modes';
            if (t === 'string') return 'strings';
        }
        if (info.stringListUse.has(name)) return 'strings';

        return 'modes';
    }

    objectVarType(name, init, scope) {
        const info = scope.info;
        let isMode = info.modeUse.has(name);
        if (!isMode && !info.keywordUse.has(name)) {
            // objects that look like modes are modes even when only read from
            const keys = init.properties.map(propKey);
            isMode = keys.length === 0 || keys.some(function (k) { return MODE_KEYS.includes(k); });
        }
        if (isMode) return 'mode';

        return this.withScope(scope, () => this.mapType(init));
    }

    mapType(node) {
        const allStrings = node.properties.every((p) => this.typeOf(p.value) === 'string');

        return allStrings ? 'map' : 'objmap';
    }

    withScope(scope, cb) {
        const index = this.scopes.indexOf(scope);
        if (index === this.scopes.length - 1 || index < 0) return cb();
        const saved = this.scopes;
        this.scopes = this.scopes.slice(0, index + 1);
        try {
            return cb();
        } finally {
            this.scopes = saved;
        }
    }

    memberType(node) {
        const objType = this.typeOf(node.object);
        if (objType === 'hljs') {
            const name = node.property.name;
            if (/_RE$/.test(name)) return 'string';
            if (HLJS_MODE_FUNCTIONS.concat(['getLanguage', 'requireLanguage']).includes(name)) return 'function';

            return 'mode';
        }
        if (objType === 'import') {
            const imp = this.imports_.get(node.object.name);
            if (imp.kind === 'module' && !node.computed && imp.exports[node.property.name]) {
                return imp.exports[node.property.name];
            }

            return 'unknown';
        }
        if (node.computed) {
            if (objType === 'modes') return 'mode';
            if (objType === 'strings' || objType === 'string' || objType === 'match') return 'string';

            return 'unknown';
        }
        const prop = node.property.name;
        if (objType === 'match') {
            if (prop === 'index') return 'number';
            if (prop === 'input') return 'string';

            return 'unknown';
        }
        if (prop === 'length') return 'number';
        if (objType === 'mode') {
            const t = MODE_PROPS[prop];
            if (t === 'keywords') return 'map';
            if (t === 'sublanguage') return 'unknown';
            if (t) return t;
            // a property that is not known to the highlighter: from the object literal, if there is one
            const literal = this.literalOf(node.object);
            const value = literal && literal.properties.find(function (p) { return p.type === 'Property' && propKey(p) === prop; });

            return value ? this.typeOf(value.value) : 'any';
        }
        if (objType === 'map') return 'string';
        if (objType === 'objmap' || objType === 'any') return 'any';

        return 'unknown';
    }

    /**
     * A function passed by reference, like `list.map(keywordWrapper)`: its Java
     * name (a method of this class or of a module) and return type; null if the
     * node is not such a reference.
     */
    functionReference(node) {
        if (!node) return null;
        if (node.type === 'Identifier' && this.functionNode(node.name)) {
            const info = this.methodFor(this.functionNode(node.name), node.name);
            if (info.params.length !== 1 || info.captured.length) {
                throw new TranspileError('Only functions of one parameter can be passed by reference', node);
            }

            return { name: info.name, returnType: info.returnType };
        }
        if (node.type === 'MemberExpression' && !node.computed && node.object.type === 'Identifier' &&
            this.typeOf(node.object) === 'import') {
            const imp = this.imports_.get(node.object.name);
            const fnInfo = imp.kind === 'module' && imp.functions && imp.functions[node.property.name];
            if (fnInfo) {
                this.use(imp.className);

                return { name: imp.className + '.' + node.property.name, returnType: fnInfo };
            }
        }

        return null;
    }

    /** The object literal a variable was initialized with, null if it is no such variable. */
    literalOf(node) {
        if (node.type !== 'Identifier') return null;
        const found = this.lookup(node.name);
        const init = found && found.decl.init;

        return init && init.type === 'ObjectExpression' ? init : null;
    }

    /** The type of the result of a callback (a function with one parameter of the given type). */
    lambdaType(fn, paramType) {
        if (!isFunction(fn) || fn.params.length !== 1) return 'unknown';
        let result = fn.body;
        if (fn.body.type === 'BlockStatement') {
            const ret = findReturn(fn.body);
            if (!ret || !ret.argument) return 'unknown';
            result = ret.argument;
        }

        return this.withFunctionScope(fn, () => this.typeOf(result), [{ name: paramNames(fn.params[0])[0], type: paramType }]);
    }

    callType(node) {
        const callee = node.callee;
        if (callee.type === 'MemberExpression') {
            const method = callee.property.name;
            const objType = this.typeOf(callee.object);
            if (objType === 'hljs') {
                if (HLJS_MODE_FUNCTIONS.includes(method)) return 'mode';
                if (method === 'getLanguage' || method === 'requireLanguage') return 'lang';
            }
            if (objType === 'import' && this.imports_.get(callee.object.name).kind === 'regex') return 'string';
            if (callee.object.type === 'Literal' && callee.object.regex && method === 'test') return 'boolean';
            if (method === 'match' && objType === 'string') return 'any';
            if (callee.object.type === 'Identifier' && callee.object.name === 'Object' && method === 'assign') {
                return this.typeOf(node.arguments[0]);
            }
            if (callee.object.type === 'Identifier' && callee.object.name === 'Object' && method === 'keys') return 'strings';
            if (method === 'filter') return objType;
            if (method === 'includes') return 'boolean';
            if (method === 'rawDefinition') return 'mode';
            if (method === 'concat') {
                if (objType === 'string') return 'string';
                if (node.arguments.length === 1 && node.arguments[0].type === 'SpreadElement' &&
                    this.typeOf(node.arguments[0].argument) === 'modelists') return 'modes';
                if (objType === 'modes' || objType === 'strings' || objType === 'values') return objType;
                for (const a of node.arguments) {
                    if (a.type === 'Literal' && a.value === 'self') return 'modes';
                    const t = this.typeOf(a);
                    if (t === 'mode' || t === 'modes') return 'modes';
                    if (t === 'string' || t === 'strings') return 'strings';
                }

                return 'modes';
            }
            if (method === 'split') return 'strings';
            if (method === 'map') {
                const ref = this.functionReference(node.arguments[0]);
                if (ref) return ref.returnType === 'string' ? 'strings' : ref.returnType === 'mode' ? 'modes' : 'unknown';
                const t = this.lambdaType(node.arguments[0], objType === 'modes' ? 'mode' : objType === 'values' ? 'any' : 'string');
                if (t === 'mode') return 'modes';
                if (t === 'modes') return 'modelists';

                return 'strings';
            }
            if (method === 'find' && objType === 'modes') return 'mode';
            if (method === 'findIndex' || method === 'indexOf') return 'number';
            if (method === 'has' && objType === 'stringset') return 'boolean';
            if (method === 'slice') return objType === 'string' ? 'string' : objType;
            if (method === 'reverse') return objType;
            if (['join', 'trim', 'replace', 'toString'].includes(method)) return 'string';
        }
        if (callee.type === 'Identifier') {
            const imp = !this.lookup(callee.name) && this.imports_.get(callee.name);
            if (imp && imp.kind === 'language') return 'mode';
            if (imp && imp.kind === 'regexfn') return 'string';
            const found = this.lookup(callee.name);
            if (found && this.identifierType(callee.name) === 'predicate') return 'boolean';
            const fn = this.functionNode(callee.name);
            if (fn) return this.methodFor(fn, callee.name).returnType;
        }

        return 'unknown';
    }

    functionNode(name) {
        const found = this.lookup(name);
        if (!found || found.decl.kind === 'param') return null;

        return found.scope.info.functions.get(name) || null;
    }

    // ---- helper methods -------------------------------------------------

    /**
     * Describes the Java method for a function declared in the definition:
     * its parameters (with types inferred from the calls) and the local
     * variables it uses, which are passed as extra arguments.
     */
    methodFor(fn, name) {
        if (this.methodInfo.has(fn)) return this.methodInfo.get(fn);
        const found = this.lookup(name);
        const outer = found.scope;
        const info = {
            name: this.methodName(name),
            params: [].concat(...fn.params.map(function (p, i) {
                if (p.type === 'ObjectPattern' || (p.type === 'AssignmentPattern' && p.left.type === 'ObjectPattern')) {
                    return paramNames(p).map(function (n) { return { name: n, type: null, default: null, arg: i, key: n }; });
                }

                return [{ name: paramName(p), type: null, default: p.type === 'AssignmentPattern' ? p.right : null, arg: i }];
            })),
            captured: [],
            returnType: 'unknown',
            emitted: false,
        };
        this.methodInfo.set(fn, info);

        // parameter types from the arguments of the calls, in the scope of the function and the scopes within
        const calls = [];
        this.scopes.slice(this.scopes.indexOf(outer)).forEach(function (scope) {
            (scope.info.calls.get(name) || []).forEach(function (c) { calls.push({ scope: scope, call: c }); });
        });
        // the types of the JSDoc comment take precedence
        const jsdoc = jsdocParamTypes(this.source, this.comments.comments, fn);
        info.params.forEach((p) => {
            const t = p.key ? (jsdoc['#' + p.arg] || {})[p.key] : jsdoc[p.name];
            if (t) p.type = t;
        });
        info.params.forEach((p) => {
            if (p.type) return;
            for (const c of calls) {
                const a = this.argumentFor(c.call, p);
                if (a) {
                    const t = this.withScope(c.scope, () => this.typeOf(a));
                    if (JAVA_TYPES[t] && t !== 'null' && t !== 'any') {
                        p.type = t;
                        break;
                    }
                }
            }
        });
        // parameters that are never passed: from their default value or their use in the function
        info.params.forEach((p) => {
            if (!p.type && p.default) {
                const t = this.withScope(outer, () => this.typeOf(p.default));
                if (JAVA_TYPES[t] && t !== 'null') p.type = t;
            }
            if (!p.type) p.type = this.paramTypeFromUse(fn, p.name);
        });

        // local variables of the enclosing scopes used by the function, and by the functions it calls
        const callees = [];
        const capture = (v) => {
            if (info.captured.some(function (c) { return c.name === v; })) return;
            info.captured.push({ name: v, type: v === 'hljs' ? 'hljs' : null });
        };
        Array.from(freeVariables(fn)).forEach((v) => {
            if (v === name) return;
            if (v === 'hljs') {
                capture('hljs');
                return;
            }
            const f = this.lookup(v);
            if (!f) return;
            if (f.decl.kind === 'function' || (f.decl.init && isFunction(f.decl.init))) {
                const callee = f.scope.info.functions.get(v);
                if (callee && callee !== fn) callees.push({ fn: callee, name: v });
                return;
            }
            capture(v);
        });
        callees.forEach((c) => {
            this.withScope(outer, () => this.methodFor(c.fn, c.name)).captured.forEach((cc) => capture(cc.name));
        });

        this.withScope(outer, () => {
            info.captured.forEach((c) => {
                if (c.type === null) c.type = this.identifierType(c.name);
            });
        });

        // the return type
        this.withFunctionScope(fn, () => {
            if (fn.body.type !== 'BlockStatement') {
                info.returnType = this.typeOf(fn.body);
                if (info.returnType === 'empty') info.returnType = 'modes';

                return;
            }
            const ret = fn.body.body.find(function (s) { return s.type === 'ReturnStatement'; });
            if (ret && ret.argument) {
                info.returnType = this.typeOf(ret.argument);
                if (info.returnType === 'empty') info.returnType = 'modes';
            } else {
                info.returnType = 'void';
            }
        }, info.params);

        return info;
    }

    /** The argument of a call for a parameter of a method (see methodFor), null when there is none. */
    argumentFor(callNode, p) {
        const a = callNode.arguments[p.arg];
        if (!a || !p.key) return a || null;
        // a destructured parameter with a default value: the default {} has no properties
        if (a.type !== 'ObjectExpression') throw new TranspileError('Expected an object literal argument', a);
        const prop = a.properties.find(function (q) { return propKey(q) === p.key; });

        return prop ? prop.value : null;
    }

    methodName(name) {
        let n = javaName(name);
        if (RESERVED_NAMES.has(n)) n = n + '_';

        return n;
    }

    paramTypeFromUse(fn, name) {
        let type = null;
        const visit = (node, parent) => {
            if (!node || typeof node.type !== 'string' || type) return;
            if (node.type === 'LogicalExpression' && node.left.type === 'Identifier' && node.left.name === name) {
                if (node.right.type === 'ObjectExpression') type = 'mode';
            }
            if (node.type === 'Property' && node.value.type === 'Identifier' && node.value.name === name) {
                const t = MODE_PROPS[propKey(node)];
                if (t && JAVA_TYPES[t]) type = t;
            }
            // list.includes(item), list.join(" ")
            if (node.type === 'CallExpression' && node.callee.type === 'MemberExpression' &&
                node.callee.object.type === 'Identifier' && node.callee.object.name === name &&
                ['includes', 'join'].includes(node.callee.property.name)) {
                type = 'strings';
            }
            for (const k of Object.keys(node)) {
                if (k === 'loc') continue;
                const child = node[k];
                if (Array.isArray(child)) child.forEach(function (c) { visit(c, node); });
                else if (child && typeof child.type === 'string') visit(child, node);
            }
        };
        visit(fn.body, null);

        // a value of any type, like the parameter of a function that is passed by reference
        return type || 'any';
    }

    // ---- statements -----------------------------------------------------

    /**
     * @param params optional [{name, type}] with the parameter types
     */
    withFunctionScope(fn, cb, params) {
        const names = [].concat(...fn.params.map(paramNames));
        const body = fn.body.type === 'BlockStatement' ? fn.body : { type: 'BlockStatement', body: [] };
        const scope = { info: analyse(body, names), fn: fn, id: this.scopeIds = (this.scopeIds || 0) + 1 };
        (params || []).forEach(function (p) {
            if (!scope.info.vars.has(p.name)) {
                scope.info.vars.set(p.name, { init: null, kind: 'param', declIndex: -1 });
            }
            scope.info.vars.get(p.name).type = p.type;
        });
        this.scopes.push(scope);
        const savedFloor = this.commentFloor;
        if (fn.start !== undefined) this.commentFloor = fn.start;
        try {
            return cb();
        } finally {
            this.scopes.pop();
            this.commentFloor = savedFloor;
        }
    }

    emitBlock(statements, indent) {
        const lines = [];
        let prevLine = null;

        statements.forEach((stmt) => {
            if (stmt.type === 'FunctionDeclaration' ||
                (stmt.type === 'VariableDeclaration' && stmt.declarations.every((d) => d.init && isFunction(d.init)))) {
                // emitted as a method
                const decls = stmt.type === 'FunctionDeclaration' ? [{ id: stmt.id, init: stmt }] : stmt.declarations;
                decls.forEach((d) => this.emitMethod(d.init, d.id.name, stmt));
                return;
            }
            const before = lines.length;
            this.emitLeadingComments(lines, stmt, indent, prevLine);
            const leading = lines.length - before;
            const code = this.emitStatement(stmt, indent);
            if (code === null) return;
            // keep blank lines between statements that were separated in the source
            if (before > 0 && prevLine !== null && this.comments.lineOf(stmt.start) - prevLine > 1 &&
                lines[before - 1] !== '') {
                lines.splice(before, 0, '');
            }
            lines.push(code);
            this.emitTrailingComment(lines, stmt);
            prevLine = this.comments.lineOf(stmt.end);
            void leading;
        });

        return lines;
    }

    emitLeadingComments(lines, node, indent, prevLine) {
        const pad = INDENT.repeat(indent);
        // in a function expression only its own comments, not those before it
        this.comments.take(this.commentFloor || 0, node.start).forEach((c) => {
            if (prevLine !== null && this.comments.lineOf(c.start) === prevLine && lines.length) {
                lines[lines.length - 1] += ' ' + this.comments.format(c);
            } else {
                this.comments.lines(c, pad).forEach(function (l) { lines.push(l); });
            }
        });
    }

    emitTrailingComment(lines, node) {
        // comments on the same line right after the node
        const endLine = this.comments.lineOf(node.end);
        const cs = this.comments.comments.filter((c) => !this.comments.used.has(c) && c.start >= node.end &&
            this.comments.lineOf(c.start) === endLine && !/\S/.test(this.source.slice(node.end, c.start).replace(/[;,]/g, '')));
        cs.forEach((c) => {
            this.comments.used.add(c);
            lines[lines.length - 1] += ' ' + this.comments.format(c);
        });
    }

    emitStatement(stmt, indent) {
        const pad = INDENT.repeat(indent);
        switch (stmt.type) {
            case 'VariableDeclaration':
                return stmt.declarations.map((d) => {
                    let type = this.identifierType(d.id.name, d);
                    if (type === 'unknown' && !d.init && !this.scope.info.assignments.has(d.id.name)) {
                        // declared, but never used
                        return pad + 'Object ' + javaName(d.id.name) + ' = null;';
                    }
                    if (type === 'function') {
                        throw new TranspileError('Unsupported function variable', d);
                    }
                    const value = d.init ? this.emitValue(d.init, indent, type) : 'null';

                    return pad + javaType(type, d) + ' ' + javaName(d.id.name) + ' = ' + suffix(value, ';');
                }).join('\n');
            case 'ReturnStatement':
                return pad + 'return' + (stmt.argument ? ' ' + suffix(this.emitValue(stmt.argument, indent, this.returnContext), ';') : ';');
            case 'ExpressionStatement':
                return this.emitExpressionStatement(stmt.expression, indent);
            case 'EmptyStatement':
                return null;
            case 'ThrowStatement': {
                const arg = stmt.argument;
                if (arg.type !== 'NewExpression' || arg.callee.name !== 'Error' || arg.arguments.length !== 1) {
                    throw new TranspileError('Only `throw new Error(message)` is supported', stmt);
                }

                return pad + 'throw new IllegalStateException(' + suffix(this.emitValue(arg.arguments[0], indent, 'string'), ');');
            }
            case 'IfStatement': {
                const block = (b) => this.emitBlock(b.type === 'BlockStatement' ? b.body : [b], indent + 1).join('\n');
                let out = pad + 'if (' + this.emitTest(stmt.test, indent) + ') {\n' + block(stmt.consequent) + '\n' + pad + '}';
                let alternate = stmt.alternate;
                while (alternate && alternate.type === 'IfStatement') {
                    out += ' else if (' + this.emitTest(alternate.test, indent) + ') {\n' + block(alternate.consequent) + '\n' +
                        pad + '}';
                    alternate = alternate.alternate;
                }
                if (alternate) out += ' else {\n' + block(alternate) + '\n' + pad + '}';

                return out;
            }
            case 'ForOfStatement': {
                // for (const x of list)
                const decl = stmt.left;
                if (decl.type !== 'VariableDeclaration' || decl.declarations.length !== 1 || decl.declarations[0].id.type !== 'Identifier') {
                    throw new TranspileError('Unsupported for...of', stmt);
                }
                const name = decl.declarations[0].id.name;
                const listType = this.typeOf(stmt.right);
                if (listType !== 'modes' && listType !== 'strings') throw new TranspileError('Unsupported for...of over ' + listType, stmt);
                const elementType = listType === 'modes' ? 'mode' : 'string';
                const body = stmt.body.type === 'BlockStatement' ? stmt.body : { type: 'BlockStatement', body: [stmt.body] };
                const fake = { type: 'FunctionExpression', params: [{ type: 'Identifier', name: name }], body: body };

                return this.withFunctionScope(fake, () => pad + 'for (' + javaType(elementType) + ' ' + javaName(name) + ' : ' +
                    this.emitValue(stmt.right, indent, listType) + ') {\n' + this.emitBlock(body.body, indent + 1).join('\n') +
                    '\n' + pad + '}', [{ name: name, type: elementType }]);
            }
            default:
                throw new TranspileError('Unsupported statement ' + stmt.type, stmt);
        }
    }

    emitExpressionStatement(expr, indent) {
        const pad = INDENT.repeat(indent);
        if (expr.type === 'AssignmentExpression') {
            const left = expr.left;
            if (left.type === 'MemberExpression' && !left.computed && this.typeOf(left.object) === 'map') {
                // map.key = value, map.key += value
                const target = this.emitValue(left.object, indent, null);
                const key = javaString(left.property.name);
                let value = this.emitValue(expr.right, indent, 'string');
                if (expr.operator === '+=') {
                    value = target + '.get(' + key + ') + ' + value;
                } else if (expr.operator !== '=') {
                    throw new TranspileError('Unsupported assignment operator ' + expr.operator, expr);
                }

                return pad + target + '.put(' + key + ', ' + suffix(value, ');');
            }
            const leftType = this.typeOf(left);
            if (left.type === 'Identifier' && this.redeclared && this.redeclared.has(left.name) && expr.operator === '=') {
                // the parameter, renamed, is redeclared (see emitMethod)
                this.redeclared.delete(left.name);
                this.renamed = left.name;
                try {
                    return pad + javaType(leftType, expr) + ' ' + javaName(left.name) + ' = ' +
                        suffix(this.emitValue(expr.right, indent, leftType), ';');
                } finally {
                    this.renamed = null;
                }
            }
            const target = this.emitLValue(left, indent);
            if (expr.operator === '=') {
                return pad + target + ' = ' + suffix(this.emitValue(expr.right, indent, leftType), ';');
            }
            if (expr.operator === '+=' && (leftType === 'string' || leftType === 'number')) {
                return pad + target + ' += ' + suffix(this.emitValue(expr.right, indent, leftType), ';');
            }
            throw new TranspileError('Unsupported assignment operator ' + expr.operator, expr);
        }
        if (expr.type === 'CallExpression' && expr.callee.type === 'MemberExpression') {
            const method = expr.callee.property.name;
            const obj = expr.callee.object;
            if (method === 'pop' && expr.arguments.length === 0) {
                const objType = this.typeOf(obj);
                if (objType !== 'modes' && objType !== 'strings') throw new TranspileError('Unsupported pop', expr);
                this.use('Js');

                return pad + 'pop(' + this.emitValue(obj, indent, objType) + ');';
            }
            if (method === 'push' || method === 'unshift') {
                const objType = this.typeOf(obj);
                const elementType = objType === 'strings' ? 'string' : 'mode';
                const target = this.emitValue(obj, indent, objType === 'any' ? 'modes' : objType);
                const args = expr.arguments.map((a) => this.emitValue(a, indent + 1, elementType));
                if (method === 'unshift') {
                    if (args.length !== 1) throw new TranspileError('unshift needs one argument', expr);

                    return pad + target + '.add(0, ' + suffix(args[0], ');');
                }

                return args.map((a) => pad + target + '.add(' + suffix(a, ');')).join('\n');
            }
            if (method === 'forEach') {
                const fn = expr.arguments[0];
                if (!isFunction(fn) || fn.params.length !== 1) {
                    throw new TranspileError('Unsupported forEach callback', fn);
                }
                const objType = this.typeOf(obj);
                const elementType = objType === 'strings' ? 'string' : 'mode';
                const param = fn.params[0].name;

                return this.withFunctionScope(fn, () => {
                    const statements = fn.body.type === 'BlockStatement' ? fn.body.body
                        : [{ type: 'ExpressionStatement', expression: fn.body, start: fn.body.start, end: fn.body.end, loc: fn.body.loc }];
                    const body = this.emitBlock(statements, indent + 1);

                    return pad + 'for (' + javaType(elementType) + ' ' + javaName(param) + ' : ' +
                        this.emitValue(obj, indent, objType) + ') {\n' + body.join('\n') + '\n' + pad + '}';
                }, [{ name: param, type: elementType }]);
            }
            if (obj.type === 'Identifier' && obj.name === 'Object' && method === 'assign' &&
                this.typeOf(expr.arguments[0]) === 'map') {
                // Object.assign(keywords, KEYWORDS): adds to the (shared) map
                this.use('Js');
                const target = expr.arguments[0].type === 'MemberExpression'
                    ? 'keywordMap(' + this.emitMember(expr.arguments[0], indent, true) + ')'
                    : this.emitValue(expr.arguments[0], indent, 'map');

                return expr.arguments.slice(1).map((src) => {
                    if (this.typeOf(src) !== 'map') throw new TranspileError('Object.assign needs maps', src);

                    return pad + call(target + '.putAll', [this.emitValue(src, indent, 'map')], indent) + ';';
                }).join('\n');
            }
            if (obj.type === 'Identifier' && obj.name === 'Object' && method === 'assign') {
                const target = this.emitValue(expr.arguments[0], indent, 'mode');
                const parts = [];
                expr.arguments.slice(1).forEach((src) => {
                    if (src.type !== 'ObjectExpression') throw new TranspileError('Object.assign needs object literals', src);
                    parts.push(src);
                });
                const props = [].concat(...parts.map((p) => p.properties));
                const chain = this.emitModeProperties({ properties: props, start: parts[0].start, end: parts[parts.length - 1].end, loc: parts[0].loc }, indent);

                return pad + suffix(target + chain, ';');
            }
        }
        if (expr.type === 'Literal') {
            // a no-op expression in the source
            return null;
        }
        if (expr.type === 'CallExpression') {
            // a call for its side effects, like response.ignoreMatch() or a helper function
            return pad + suffix(this.emitValue(expr, indent, null), ';');
        }
        if (expr.type === 'UnaryExpression' && expr.operator === 'delete' && expr.argument.type === 'MemberExpression' &&
            this.typeOf(expr.argument.object) === 'mode') {
            // delete mode.key, delete mode[key]
            const target = expr.argument;
            const key = target.computed ? this.emitValue(target.property, indent, 'string') : javaString(target.property.name);

            return pad + this.emitValue(target.object, indent, 'mode') + '.remove(' + key + ');';
        }
        throw new TranspileError('Unsupported expression statement ' + expr.type, expr);
    }

    emitLValue(node, indent) {
        if (node.type === 'Identifier') return javaName(node.name);
        if (node.type === 'MemberExpression') {
            if (!node.computed && this.typeOf(node.object) === 'mode' && MODE_PROPS[node.property.name] === undefined) {
                throw new TranspileError('Unsupported assignment to ' + node.property.name, node);
            }

            return this.emitMember(node, indent, true);
        }
        throw new TranspileError('Unsupported assignment target', node);
    }

    // ---- functions ------------------------------------------------------

    emitMethod(fn, name, stmt) {
        const info = this.methodFor(fn, name);
        if (info.emitted) return;
        info.emitted = true;
        const leading = [];
        this.comments.take(0, stmt.start).forEach((c) => {
            this.comments.lines(c, INDENT).forEach(function (l) { leading.push(l); });
        });
        // A parameter that is assigned once, at the start (like `x = x || []`), is redeclared there, so
        // that it is effectively final and can be used in lambdas: the Java parameter is renamed.
        const redeclared = new Set();
        if (fn.body.type === 'BlockStatement') {
            const assigned = {};
            const visit = function (node) {
                if (!node || typeof node.type !== 'string') return;
                if (node.type === 'AssignmentExpression' && node.left.type === 'Identifier') {
                    assigned[node.left.name] = (assigned[node.left.name] || 0) + 1;
                }
                for (const k of Object.keys(node)) {
                    if (k === 'loc') continue;
                    const child = node[k];
                    if (Array.isArray(child)) child.forEach(visit);
                    else if (child && typeof child.type === 'string') visit(child);
                }
            };
            visit(fn.body);
            fn.body.body.forEach(function (st) {
                const e = st.type === 'ExpressionStatement' && st.expression;
                if (e && e.type === 'AssignmentExpression' && e.operator === '=' && e.left.type === 'Identifier' &&
                    assigned[e.left.name] === 1 && info.params.some(function (p) { return p.name === e.left.name; })) {
                    redeclared.add(e.left.name);
                }
            });
        }
        const params = info.params.concat(info.captured).map(function (p) {
            return (p.type === 'hljs' ? 'Hljs' : javaType(p.type, fn)) + ' ' + javaName(p.name) + (redeclared.has(p.name) ? '_' : '');
        });
        if (info.returnType === 'predicate') this.use('Predicate');
        const returnType = info.returnType === 'void' ? 'void' : javaType(info.returnType, fn);
        const lines = this.withFunctionScope(fn, () => {
            const saved = this.returnContext;
            const savedRedeclared = this.redeclared;
            this.returnContext = info.returnType;
            this.redeclared = redeclared;
            try {
                if (fn.body.type !== 'BlockStatement') {
                    return [INDENT.repeat(2) + 'return ' + suffix(this.emitValue(fn.body, 2, info.returnType), ';')];
                }

                return this.emitBlock(fn.body.body, 2);
            } finally {
                this.returnContext = saved;
                this.redeclared = savedRedeclared;
            }
        }, info.params.concat(info.captured.filter(function (c) { return c.type !== 'hljs'; })));
        this.methods.push(leading.concat([
            INDENT + (info.access || 'private') + ' static ' + returnType + ' ' + info.name + '(' + params.join(', ') + ') {',
        ]).concat(lines).concat([INDENT + '}']).join('\n'));
    }

    emitCallOfMethod(node, indent) {
        const fn = this.functionNode(node.callee.name);
        if (!fn) throw new TranspileError('Unknown function ' + node.callee.name, node);
        const info = this.methodFor(fn, node.callee.name);
        const args = info.params.map((p) => {
            const a = this.argumentFor(node, p);
            if (!a) return p.default ? this.emitValue(p.default, indent + 1, p.type) : 'null';

            return this.emitValue(a, indent + 1, p.type);
        });
        info.captured.forEach(function (c) { args.push(javaName(c.name)); });

        return call(info.name, args, indent);
    }

    /** A callback, as a Java lambda. */
    emitLambda(fn, indent, paramType) {
        if (!isFunction(fn) || fn.params.length !== 1 || fn.params[0].type !== 'Identifier') {
            throw new TranspileError('Unsupported callback', fn);
        }
        const param = fn.params[0].name;
        let result = fn.body;
        if (fn.body.type === 'BlockStatement') {
            const body = fn.body.body;
            if (body.length !== 1 || body[0].type !== 'ReturnStatement') {
                // statements that return a value
                const returnType = this.lambdaType(fn, paramType);

                return this.withFunctionScope(fn, () => {
                    const saved = this.returnContext;
                    this.returnContext = returnType;
                    try {
                        return javaName(param) + ' -> {\n' + this.emitBlock(body, indent + 1).join('\n') + '\n' +
                            INDENT.repeat(indent) + '}';
                    } finally {
                        this.returnContext = saved;
                    }
                }, [{ name: param, type: paramType }]);
            }
            result = body[0].argument;
        }

        return this.withFunctionScope(fn, () => {
            return javaName(param) + ' -> ' + this.emitValue(result, indent, null);
        }, [{ name: param, type: paramType }]);
    }

    /** A compiler extension, (mode, parent) => ..., as a lambda. */
    emitExtension(fn, indent) {
        if (!isFunction(fn)) throw new TranspileError('A compiler extension must be a function', fn);
        const names = [].concat(...fn.params.map(paramNames));
        if (names.length > 2) throw new TranspileError('A compiler extension has two parameters', fn);
        while (names.length < 2) names.push(names.length ? 'parent' : 'mode');
        const params = [{ name: names[0], type: 'mode' }, { name: names[1], type: 'mode' }];

        return this.withFunctionScope(fn, () => {
            const saved = this.returnContext;
            this.returnContext = 'void';
            try {
                const head = '(' + javaName(names[0]) + ', ' + javaName(names[1]) + ') -> ';
                if (fn.body.type !== 'BlockStatement') return head + this.emitValue(fn.body, indent, null);

                return head + '{\n' + this.emitBlock(fn.body.body, indent + 1).join('\n') + '\n' + INDENT.repeat(indent) + '}';
            } finally {
                this.returnContext = saved;
            }
        }, params);
    }

    // ---- expressions ----------------------------------------------------

    /**
     * @param expected the expected type, determines how literals are emitted
     */
    emitValue(node, indent, expected) {
        switch (node.type) {
            case 'Literal':
                if (node.regex) {
                    if (node.regex.flags && node.regex.flags !== 'g') {
                        throw new TranspileError('Unsupported regex flags ' + node.regex.flags, node);
                    }

                    return javaString(node.regex.pattern);
                }
                if (typeof node.value === 'string') {
                    if (node.value === 'self' && expected === 'mode') return 'Mode.SELF';

                    return javaString(node.value);
                }
                if (node.value === null) return 'null';
                // `false` where a regex is expected (like `hljs.COMMENT(begin, false)`) means: no regex
                if (node.value === false && expected === 'string') return 'null';
                if (typeof node.value === 'number' && !Number.isInteger(node.value)) {
                    throw new TranspileError('Unsupported number ' + node.value, node);
                }

                return String(node.value);
            case 'Identifier':
                if (node.name === 'undefined') return 'null';
                if (!this.lookup(node.name) && this.imports_.has(node.name) && this.imports_.get(node.name).kind === 'binding') {
                    const imp = this.imports_.get(node.name);
                    this.use(imp.className);
                    // a mode of a module is a local variable of the definition (see transpile)
                    return imp.type === 'mode' ? javaName(node.name) : imp.className + '.' + imp.exportName;
                }
                if (!this.lookup(node.name) && node.name !== 'hljs') {
                    throw new TranspileError('Unknown variable ' + node.name, node);
                }
                if (this.renamed === node.name) return javaName(node.name) + '_';

                return javaName(node.name);
            case 'ObjectExpression':
                return this.emitObject(node, indent, expected);
            case 'ArrayExpression':
                return this.emitArray(node, indent, expected);
            case 'BinaryExpression':
                return this.emitBinary(node, indent);
            case 'LogicalExpression':
                if (node.operator === '||') {
                    this.use('Js');
                    const t = expected || this.typeOf(node);

                    return call('or', [this.emitValue(node.left, indent, t), this.emitValue(node.right, indent, t)], indent);
                }
                throw new TranspileError('Unsupported logical operator ' + node.operator, node);
            case 'MemberExpression': {
                const code = this.emitMember(node, indent, false);
                const extra = !node.computed && this.typeOf(node.object) === 'mode' && MODE_PROPS[node.property.name] === undefined;
                const t = this.typeOf(node);
                if (extra && t !== 'any' && JAVA_TYPES[t]) {
                    // a property that is not known to the highlighter, of a known type
                    return t === 'modes' || t === 'strings' ? 'Js.<' + (t === 'modes' ? 'Mode' : 'String') + '>asList(' + code + ')'
                        : '((' + javaType(t) + ') ' + code + ')';
                }
                if (t === 'any' && expected && JAVA_TYPES[expected] && expected !== 'any') {
                    if (expected === 'modes' || expected === 'strings') {
                        this.use('Js');

                        return 'Js.<' + (expected === 'modes' ? 'Mode' : 'String') + '>asList(' + code + ')';
                    }

                    return '((' + javaType(expected) + ') ' + code + ')';
                }

                return code;
            }
            case 'CallExpression':
                return this.emitCall(node, indent, expected);
            case 'TemplateLiteral':
                return this.emitTemplate(node, indent);
            case 'ConditionalExpression': {
                const t = expected || this.typeOf(node);

                return '(' + this.emitTest(node.test, indent) + ' ? ' + this.emitValue(node.consequent, indent, t) + ' : ' +
                    this.emitValue(node.alternate, indent, t) + ')';
            }
            case 'UnaryExpression':
                if (node.operator === '!') {
                    const operand = this.emitValue(node.argument, indent, null);
                    if (this.typeOf(node.argument) === 'boolean') {
                        return '!' + (node.argument.type === 'BinaryExpression' ? '(' + operand + ')' : operand);
                    }
                    this.use('Js');

                    return '!truthy(' + operand + ')';
                }
                if (node.operator === '-' && this.typeOf(node.argument) === 'number') {
                    return '-' + this.emitValue(node.argument, indent, 'number');
                }
                throw new TranspileError('Unsupported operator ' + node.operator, node);
            case 'NewExpression':
                if (node.callee.type === 'Identifier' && node.callee.name === 'RegExp' && node.arguments.length === 1) {
                    // the regex is kept as its source
                    return this.emitValue(node.arguments[0], indent, 'string');
                }
                if (node.callee.type === 'Identifier' && node.callee.name === 'Set' && node.arguments.length === 1 &&
                    this.typeOf(node.arguments[0]) === 'strings') {
                    this.use('HashSet');

                    return call('new HashSet<>', [this.emitValue(node.arguments[0], indent, 'strings')], indent);
                }
                throw new TranspileError('Unsupported new expression', node);
            case 'FunctionExpression':
            case 'ArrowFunctionExpression': {
                const t = this.typeOf(node);
                if (t === 'predicate') return this.emitLambda(node, indent, expected === 'predicate' ? 'string' : 'string');
                if (t === 'callback') return this.emitCallback(node, indent);
                throw new TranspileError('Unsupported function expression', node);
            }
            default:
                throw new TranspileError('Unsupported expression ' + node.type, node);
        }
    }

    /** A callback of a mode, (match, response) => ..., as a lambda. */
    emitCallback(fn, indent) {
        const names = [].concat(...fn.params.map(paramNames));
        if (names.length !== 2) throw new TranspileError('A callback has two parameters', fn);
        const params = [{ name: names[0], type: 'match' }, { name: names[1], type: 'response' }];

        return this.withFunctionScope(fn, () => {
            const saved = this.returnContext;
            this.returnContext = 'void';
            try {
                const head = '(' + javaName(names[0]) + ', ' + javaName(names[1]) + ') -> ';
                if (fn.body.type !== 'BlockStatement') return head + this.emitValue(fn.body, indent, null);
                const body = this.emitBlock(fn.body.body, indent + 1);

                return head + '{\n' + body.join('\n') + '\n' + INDENT.repeat(indent) + '}';
            } finally {
                this.returnContext = saved;
            }
        }, params);
    }

    /** A condition, as a Java boolean expression (with JavaScript truthiness). */
    emitTest(node, indent) {
        if (node.type === 'LogicalExpression' && (node.operator === '||' || node.operator === '&&')) {
            const part = (n) => {
                const code = this.emitTest(n, indent);

                return n.type === 'LogicalExpression' && n.operator !== node.operator ? '(' + code + ')' : code;
            };

            return part(node.left) + ' ' + node.operator + ' ' + part(node.right);
        }
        const code = this.emitValue(node, indent, null);
        if (this.typeOf(node) === 'boolean') return code;
        this.use('Js');

        return 'truthy(' + code + ')';
    }

    /**
     * The arguments of a regex helper (concat, either...), all regex sources:
     * spread lists are expanded, regex values are reduced to their source.
     */
    emitRegexArgs(args, indent) {
        const spread = args.some(function (a) { return a.type === 'SpreadElement'; });
        const parts = args.map((a) => {
            if (a.type === 'SpreadElement') {
                const t = this.typeOf(a.argument);
                const list = this.emitValue(a.argument, indent + 1, t);
                if (t === 'values') {
                    this.use('Js');

                    return 'sources(' + list + ')';
                }
                if (t !== 'strings') throw new TranspileError('Cannot spread ' + t + ' into a regex', a);

                return list;
            }
            const t = this.typeOf(a);
            if (t === 'any') {
                this.use('Js');

                return 'source(' + this.emitValue(a, indent + 1, null) + ')';
            }

            return this.emitValue(a, indent + 1, 'string');
        });
        if (!spread) return parts;
        this.use('Js');
        if (args.length === 1) return [parts[0] + '.toArray(new String[0])'];

        return [call('Js.<String>concat', ['Js.<String>list()'].concat(parts), indent) + '.toArray(new String[0])'];
    }

    /** A template literal, as a string concatenation. */
    emitTemplate(node, indent) {
        const parts = [];
        node.quasis.forEach((q, i) => {
            if (q.value.cooked) parts.push(javaString(q.value.cooked));
            if (i < node.expressions.length) {
                const e = node.expressions[i];
                const code = this.emitValue(e, indent, null);
                parts.push(e.type === 'BinaryExpression' ? '(' + code + ')' : code);
                if (parts.length === 1 && this.typeOf(e) !== 'string') parts.unshift('""');
            }
        });

        return parts.length ? parts.join(' + ') : '""';
    }

    sameLine(node) {
        return node.loc.start.line === node.loc.end.line;
    }

    /**
     * Renders items between open and close, either on one line or one per
     * line, preserving comments.
     *
     * @param items [{node, render(indent) => code}]
     */
    emitList(node, items, open, close, indent, sep) {
        sep = sep === undefined ? ', ' : sep;
        const pad = INDENT.repeat(indent);
        const inner = INDENT.repeat(indent + 1);
        const rendered = items.map((item) => ({ item: item, code: item.render(indent + 1) }));

        const hasComments = this.comments.hasUnused(node.start, node.end);
        const oneLine = open + rendered.map(function (r) { return r.code; }).join(sep) + close;
        if (!hasComments && (this.sameLine(node) || items.length <= 1) && !oneLine.includes('\n') &&
            width(pad + oneLine) < MAX_LINE) {
            return oneLine;
        }
        if (items.length === 0) {
            return open + close;
        }

        const lines = [open];
        let prevLine = null;
        rendered.forEach((r, i) => {
            const start = r.item.node.start;
            this.comments.take(node.start, start).forEach((c) => {
                if (prevLine !== null && this.comments.lineOf(c.start) === prevLine) {
                    lines[lines.length - 1] += ' ' + this.comments.format(c);
                } else {
                    this.comments.lines(c, inner).forEach(function (l) { lines.push(l); });
                }
            });
            lines.push(inner + (i < rendered.length - 1 ? suffix(r.code, sep.trimEnd()) : r.code));
            prevLine = this.comments.lineOf(r.item.node.end);
        });
        this.comments.take(node.start, node.end).forEach((c) => {
            if (prevLine !== null && this.comments.lineOf(c.start) === prevLine) {
                lines[lines.length - 1] += ' ' + this.comments.format(c);
            } else {
                this.comments.lines(c, inner).forEach(function (l) { lines.push(l); });
            }
        });

        return suffix(lines.join('\n'), close);
    }

    emitObject(node, indent, expected) {
        if (expected === 'map' || expected === 'objmap' || expected === 'keywords') {
            return this.emitMap(node, indent, expected === 'keywords' ? this.mapType(node) : expected);
        }
        if (expected && expected !== 'mode' && expected !== 'unknown') {
            throw new TranspileError('Object literal where a ' + expected + ' is expected', node);
        }

        return 'new Mode()' + this.emitModeProperties(node, indent);
    }

    emitMap(node, indent, type) {
        this.use('Js');
        const valueType = type === 'map' ? 'string' : null;
        const items = node.properties.map((p) => {
            this.checkProperty(p);

            return {
                node: p,
                render: (ind) => javaString(propKey(p)) + ', ' + this.emitValue(p.value, ind, valueType),
            };
        });

        return this.emitList(node, items, type === 'map' ? 'map(' : 'objectMap(', ')', indent);
    }

    checkProperty(p) {
        if (p.type !== 'Property' || p.kind !== 'init' || p.method) {
            throw new TranspileError('Unsupported object property', p);
        }
    }

    /** The properties of a mode literal, as a chain of setter calls. */
    emitModeProperties(node, indent) {
        const items = node.properties.map((p) => {
            this.checkProperty(p);
            const key = propKey(p);

            return {
                node: p,
                render: (ind) => this.emitModeProperty(key, p.value, ind),
            };
        });
        if (!items.length) return '';
        // `.prop(...)` calls on one line, or one per line
        const pad = INDENT.repeat(indent);
        const rendered = items.map((item) => ({ item: item, code: item.render(indent + 1) }));
        const hasComments = this.comments.hasUnused(node.start, node.end);
        const oneLine = rendered.map(function (r) { return r.code; }).join('');
        if (!hasComments && this.sameLine(node) && !oneLine.includes('\n') && width(pad + 'new Mode()' + oneLine) < MAX_LINE - 10) {
            return oneLine;
        }

        const inner = INDENT.repeat(indent + 1);
        const lines = [''];
        let prevLine = null;
        rendered.forEach((r) => {
            this.comments.take(node.start, r.item.node.start).forEach((c) => {
                if (prevLine !== null && this.comments.lineOf(c.start) === prevLine) {
                    lines[lines.length - 1] += ' ' + this.comments.format(c);
                } else {
                    this.comments.lines(c, inner).forEach(function (l) { lines.push(l); });
                }
            });
            lines.push(inner + r.code);
            prevLine = this.comments.lineOf(r.item.node.end);
        });
        this.comments.take(node.start, node.end).forEach((c) => {
            if (prevLine !== null && this.comments.lineOf(c.start) === prevLine) {
                lines[lines.length - 1] += ' ' + this.comments.format(c);
            } else {
                this.comments.lines(c, inner).forEach(function (l) { lines.push(l); });
            }
        });

        return lines.join('\n');
    }

    emitModeProperty(key, value, indent) {
        const type = MODE_PROPS[key];
        const valueType = this.typeOf(value);
        if (valueType === 'null') {
            return '.set(' + javaString(key) + ', null)';
        }
        if (!type) {
            // not a mode property: kept in `extra`
            let expected = null;
            if (value.type === 'ObjectExpression') expected = this.mapType(value) === 'map' ? 'map' : 'objmap';
            let code = this.emitValue(value, indent, expected);
            if (valueType === 'callback' && isFunction(value)) code = '(Mode.Callback) ' + code;

            return call('.set', [javaString(key), code], indent);
        }
        if (MODE_SETTERS[key]) {
            return call('.' + MODE_SETTERS[key], [this.emitValue(value, indent, type)], indent);
        }
        if (type === 'extensions') {
            if (value.type !== 'ArrayExpression') throw new TranspileError('compilerExtensions must be a list of functions', value);
            const items = value.elements.map((el) => ({ node: el, render: (ind) => this.emitExtension(el, ind) }));

            return '.compilerExtensions' + this.emitList(value, items, '(', ')', indent);
        }
        if (type === 'keywords') {
            if (value.type === 'ObjectExpression') return call('.keywords', [this.emitValue(value, indent, 'keywords')], indent);
            if (valueType === 'string' || valueType === 'map') return call('.keywords', [this.emitValue(value, indent, valueType)], indent);
            if (value.type === 'MemberExpression' && !value.computed && value.property.name === 'keywords') {
                return call('.set', ['"keywords"', this.emitMember(value, indent, true)], indent);
            }
            throw new TranspileError('Cannot determine the type of keywords', value);
        }
        if (type === 'sublanguage') {
            if (valueType === 'string') return call('.subLanguage', [this.emitValue(value, indent, 'string')], indent);
            if (value.type === 'ArrayExpression' || valueType === 'strings') {
                return call('.subLanguage', [this.emitValue(value, indent, 'strings')], indent);
            }
            throw new TranspileError('Cannot determine the type of subLanguage', value);
        }
        if ((type === 'modes' || type === 'strings') && value.type === 'ArrayExpression' &&
            value.elements.some((el) => el && el.type === 'SpreadElement')) {
            return call('.' + key, [this.emitArray(value, indent, type)], indent);
        }
        if ((type === 'modes' || type === 'strings') && value.type === 'ArrayExpression') {
            // varargs setter
            const elementType = type === 'modes' ? 'mode' : 'string';
            const items = value.elements.map((el) => {
                if (el === null || el.type === 'SpreadElement') throw new TranspileError('Unsupported array element', value);

                return { node: el, render: (ind) => this.emitValue(el, ind, elementType) };
            });
            if (items.length === 0) {
                return '.' + key + '(' + (type === 'modes' ? 'new Mode[0]' : 'new String[0]') + ')';
            }

            return '.' + key + this.emitList(value, items, '(', ')', indent);
        }
        if (type === 'number' && valueType !== 'number' && valueType !== 'unknown') {
            throw new TranspileError('Expected a number for ' + key, value);
        }

        return call('.' + key, [this.emitValue(value, indent, type)], indent);
    }

    emitArray(node, indent, expected) {
        let type = expected;
        if (type !== 'modes' && type !== 'strings') {
            type = this.typeOf(node);
            if (type === 'empty' || type === 'unknown') type = 'modes';
        }
        const elementType = type === 'modes' ? 'mode' : 'string';
        this.use('Js');
        if (type === 'values') {
            // strings and regexes
            const items = node.elements.map((el) => {
                if (el === null || el.type === 'SpreadElement') throw new TranspileError('Unsupported list element', node);
                if (el.type === 'Literal' && el.regex) {
                    if (el.regex.flags) throw new TranspileError('Unsupported regex flags ' + el.regex.flags, el);

                    return { node: el, render: () => 'regexp(' + javaString(el.regex.pattern) + ')' };
                }

                return { node: el, render: (ind) => this.emitValue(el, ind, 'string') };
            });

            return this.emitList(node, items, 'Js.<Object>list(', ')', indent);
        }
        if (node.elements.some((el) => el && el.type === 'SpreadElement')) {
            // [...a, b] is a new list with the elements of a, then b
            const parts = node.elements.map((el) => {
                if (el === null) throw new TranspileError('Array holes are not supported', node);
                if (el.type === 'SpreadElement') return this.emitValue(el.argument, indent + 1, type);

                return this.emitValue(el, indent + 1, elementType);
            });

            return call('Js.<' + (type === 'modes' ? 'Mode' : 'String') + '>concat',
                ['Js.<' + (type === 'modes' ? 'Mode' : 'String') + '>list()'].concat(parts), indent);
        }
        const items = node.elements.map((el) => {
            if (el === null) throw new TranspileError('Array holes are not supported', node);

            return { node: el, render: (ind) => this.emitValue(el, ind, elementType) };
        });
        if (items.length === 0) {
            return 'Js.<' + (type === 'modes' ? 'Mode' : 'String') + '>list()';
        }

        return this.emitList(node, items, 'list(', ')', indent);
    }

    emitBinary(node, indent) {
        const operand = (n, t) => {
            const code = this.emitValue(n, indent, t);

            return n.type === 'BinaryExpression' ? '(' + code + ')' : code;
        };
        if (['===', '!==', '==', '!='].includes(node.operator)) {
            const typeofOf = (a, b) => a.type === 'UnaryExpression' && a.operator === 'typeof' && b.type === 'Literal' ? a : null;
            const t = typeofOf(node.left, node.right) || typeofOf(node.right, node.left);
            if (t) {
                // typeof x === 'string'
                const literal = t === node.left ? node.right : node.left;
                const classes = { string: 'String', number: 'Number', boolean: 'Boolean', object: 'Object' };
                const target = this.emitValue(t.argument, indent, null);
                let code;
                if (literal.value === 'undefined') {
                    code = target + ' == null';
                } else if (classes[literal.value]) {
                    code = target + ' instanceof ' + classes[literal.value];
                } else {
                    throw new TranspileError('Unsupported typeof ' + literal.value, node);
                }

                return node.operator.startsWith('!') ? '!(' + code + ')' : code;
            }
            const code = 'Objects.equals(' + this.emitValue(node.left, indent, null) + ', ' +
                this.emitValue(node.right, indent, null) + ')';
            this.use('Objects');

            return node.operator.startsWith('!') ? '!' + code : code;
        }
        if (['<', '>', '<=', '>=', '-', '*'].includes(node.operator)) {
            return operand(node.left, 'number') + ' ' + node.operator + ' ' + operand(node.right, 'number');
        }
        if (node.operator !== '+') {
            throw new TranspileError('Unsupported operator ' + node.operator, node);
        }
        const type = this.typeOf(node);
        if (type === 'number') {
            return this.emitValue(node.left, indent, 'number') + ' + ' + this.emitValue(node.right, indent, 'number');
        }
        if (type !== 'string') {
            throw new TranspileError('Cannot determine type of + expression', node);
        }
        // flatten string concatenation chains, keep line breaks of the source
        const operands = [];
        const flatten = (n) => {
            if (n.type === 'BinaryExpression' && n.operator === '+' && this.typeOf(n.left) === 'string') {
                flatten(n.left);
                flatten(n.right);
            } else {
                operands.push(n);
            }
        };
        flatten(node);
        const constant = operands.reduce(function (sum, op) {
            return sum + (op.type === 'Literal' && typeof op.value === 'string' ? op.value.length : 0);
        }, 0);
        const cont = INDENT.repeat(indent + 2);
        const codes = operands.map((op) => {
            if (op.type === 'Literal' && op.regex) {
                // like JavaScript, a regex in a string concatenation is its toString(), slashes included
                return javaString('/' + op.regex.pattern + '/' + op.regex.flags);
            }
            const code = this.emitValue(op, indent + 2, null);

            return op.type === 'BinaryExpression' ? '(' + suffix(code, ')') : code;
        });
        if (constant > MAX_CONSTANT) {
            // javac would fold the constants into a string that is too long for the class file
            return 'String.join(""' + codes.map(function (c, i) {
                return (i > 0 && operands[i].loc.start.line !== operands[i - 1].loc.end.line ? ',\n' + cont : ', ') + c;
            }).join('') + ')';
        }
        let out = '';
        codes.forEach((code, i) => {
            if (i === 0) {
                out = code;
            } else if (operands[i].loc.start.line !== operands[i - 1].loc.end.line || endsWithComment(out)) {
                out += '\n' + cont + '+ ' + code;
            } else {
                out += ' + ' + code;
            }
        });

        return out;
    }

    emitMember(node, indent, raw) {
        const obj = node.object;
        const objType = this.typeOf(obj);
        if (objType === 'hljs') {
            return 'hljs.' + node.property.name;
        }
        if (objType === 'import') {
            const imp = this.imports_.get(obj.name);
            if (imp.kind !== 'module' || node.computed || !imp.exports[node.property.name]) {
                throw new TranspileError('Unsupported use of import ' + obj.name, node);
            }
            this.use(imp.className);

            return imp.className + '.' + node.property.name;
        }
        const target = obj.type === 'Identifier' ? javaName(obj.name) : this.emitValue(obj, indent, null);
        if (node.computed) {
            if (objType === 'modes' || objType === 'strings') {
                return call(target + '.get', [this.emitValue(node.property, indent, 'number')], indent);
            }
            if (objType === 'match') {
                return call(target + '.group', [this.emitValue(node.property, indent, 'number')], indent);
            }
            if (objType === 'string') {
                this.use('Js');

                return call('charAt', [target, this.emitValue(node.property, indent, 'number')], indent);
            }
            throw new TranspileError('Unsupported computed member access on ' + objType, node);
        }
        const prop = node.property.name;
        if (objType === 'match') {
            if (prop === 'index' || prop === 'input') return target + '.' + prop + '()';
            throw new TranspileError('Unsupported property of a match ' + prop, node);
        }
        if (objType === 'objmap') {
            return target + '.get(' + javaString(prop) + ')';
        }
        if (objType === 'any') {
            this.use('Js');

            return 'Js.get(' + target + ', ' + javaString(prop) + ')';
        }
        if (prop === 'length') {
            if (objType === 'string') return target + '.length()';
            if (objType === 'modes' || objType === 'strings') return target + '.size()';
            throw new TranspileError('Unsupported length of ' + objType, node);
        }
        if (objType === 'map') {
            return target + '.get(' + javaString(prop) + ')';
        }
        if (objType === 'mode') {
            if (MODE_PROPS[prop] === undefined) {
                // not a mode property: kept in `extra`
                return target + '.get(' + javaString(prop) + ')';
            }
            if (MODE_SETTERS[prop]) return target + '.' + MODE_SETTERS[prop];
            if (MODE_PROPS[prop] === 'keywords' && !raw) {
                this.use('Js');

                return 'keywordMap(' + target + '.keywords)';
            }

            return target + '.' + prop;
        }
        throw new TranspileError('Unsupported member access on ' + objType, node);
    }

    emitCall(node, indent, expected) {
        const callee = node.callee;
        const args = node.arguments;

        if (callee.type === 'Identifier') {
            const imp = !this.lookup(callee.name) && this.imports_.get(callee.name);
            if (imp && imp.kind === 'language') {
                // the definition of another language, built upon
                if (args.length !== 1 || args[0].type !== 'Identifier' || args[0].name !== 'hljs') {
                    throw new TranspileError('A language definition must be called with hljs', node);
                }

                return 'new ' + imp.className + '().define(hljs)';
            }
            if (imp && imp.kind === 'regexfn') {
                this.use('Regex');

                return call('Regex.' + imp.exportName, this.emitRegexArgs(args, indent), indent);
            }
            if (this.lookup(callee.name) && this.identifierType(callee.name) === 'predicate') {
                // a variable holding a function
                return call(javaName(callee.name) + '.test', args.map((a) => this.emitValue(a, indent, null)), indent);
            }

            return this.emitCallOfMethod(node, indent);
        }
        if (callee.type !== 'MemberExpression' || callee.computed) {
            throw new TranspileError('Unsupported call', node);
        }

        const method = callee.property.name;
        const obj = callee.object;
        const objType = this.typeOf(obj);

        if (objType === 'hljs') {
            if (method === 'inherit') {
                return call('hljs.inherit', args.map((a) => this.emitValue(a, indent + 1, 'mode')), indent);
            }
            if (method === 'COMMENT') {
                const types = ['string', 'string', 'mode'];

                const codes = args.map((a, i) => this.emitValue(a, indent + 1, types[i]));
                while (codes.length < 2) codes.push('null');

                return call('hljs.COMMENT', codes, indent);
            }
            if (method === 'getLanguage' || method === 'requireLanguage') {
                return call('hljs.' + method, args.map((a) => this.emitValue(a, indent, 'string')), indent);
            }
            if (method === 'SHEBANG' || method === 'END_SAME_AS_BEGIN') {
                if (args.length > 1) throw new TranspileError('Too many arguments for ' + method, node);

                return call('hljs.' + method, args.map((a) => this.emitValue(a, indent + 1, 'mode')), indent);
            }
            throw new TranspileError('Unsupported hljs method ' + method, node);
        }
        if (objType === 'import') {
            const imp = this.imports_.get(obj.name);
            if (imp.kind !== 'regex' || !REGEX_FUNCTIONS.includes(method)) {
                throw new TranspileError('Unsupported call of ' + obj.name + '.' + method, node);
            }
            this.use('Regex');

            return call('Regex.' + method, this.emitRegexArgs(args, indent), indent);
        }

        if (obj.type === 'Literal' && obj.regex && method === 'test') {
            this.use('Js');

            return call('Js.test', [javaString(obj.regex.pattern), this.emitValue(args[0], indent, null)], indent);
        }
        if (obj.type === 'Identifier' && obj.name === 'Object' && method === 'keys') {
            if (this.typeOf(args[0]) !== 'mode') throw new TranspileError('Object.keys is only supported on modes', node);

            return this.emitValue(args[0], indent, 'mode') + '.keys()';
        }
        if (obj.type === 'Identifier' && obj.name === 'Object' && method === 'assign') {
            // Object.assign(target, ...sources) as an expression: the target
            this.use('Js');

            return call('Js.assign', args.map((a) => this.emitValue(a, indent + 1, 'mode')), indent);
        }

        const self = () => this.emitValue(obj, indent, objType);

        switch (method) {
            case 'rawDefinition':
                return self() + '.rawDefinition()';
            case 'concat': {
                if (objType === 'string') {
                    return [self()].concat(args.map((a) => this.emitValue(a, indent, null))).join(' + ');
                }
                if (args.length === 1 && args[0].type === 'SpreadElement' && this.typeOf(args[0].argument) === 'modelists') {
                    // [].concat(...lists) flattens the lists
                    this.use('Js');
                    const flat = call('flat', [this.emitValue(args[0].argument, indent + 1, 'modelists')], indent);

                    return obj.type === 'ArrayExpression' && obj.elements.length === 0 ? flat
                        : call('Js.<Mode>concat', [this.emitValue(obj, indent + 1, 'modes'), flat], indent);
                }
                const type = this.typeOf(node);
                const elementType = type === 'modes' ? 'mode' : 'string';
                const parts = args.map((a) => {
                    if (a.type === 'Literal' && a.value === 'self' && type === 'modes') return 'Mode.SELF';
                    const t = this.typeOf(a);
                    if (t === type || t === 'empty') return this.emitValue(a, indent + 1, type);
                    // strings can be added to a list of values
                    if (type === 'values' && (t === 'strings' || t === 'string')) return this.emitValue(a, indent + 1, t);
                    if (t === elementType || (elementType === 'mode' && a.type === 'ObjectExpression')) {
                        return this.emitValue(a, indent + 1, elementType);
                    }
                    throw new TranspileError('Cannot determine type of concat argument', a);
                });
                this.use('Js');

                return call('Js.<' + { modes: 'Mode', strings: 'String', values: 'Object' }[type] + '>concat',
                    [this.emitValue(obj, indent + 1, type)].concat(parts), indent);
            }
            case 'join':
                this.use('Js');

                return call('join', [self(), this.emitValue(args[0], indent, 'string')], indent);
            case 'split': {
                const sep = args[0];
                if (sep.type === 'Literal' && typeof sep.value === 'string') {
                    this.use('Js');

                    return call('split', [self(), javaString(sep.value)], indent);
                }
                throw new TranspileError('Unsupported split separator', sep);
            }
            case 'trim':
                return self() + '.trim()';
            case 'toString':
                if (objType !== 'string') throw new TranspileError('Unsupported toString', node);

                return self();
            case 'reverse':
                this.use('Js');

                return call('reverse', [self()], indent);
            case 'slice': {
                if (objType === 'string') {
                    this.use('Js');

                    return call('Js.slice', [self()].concat(args.map((a) => this.emitValue(a, indent, 'number'))), indent);
                }
                if (args.length > 1) throw new TranspileError('slice with end is not supported', node);
                this.use('Js');

                return call('slice', [self(), args.length ? this.emitValue(args[0], indent, 'number') : '0'], indent);
            }
            case 'map': {
                if (objType !== 'strings' && objType !== 'values') {
                    throw new TranspileError('map is only supported on lists of strings', node);
                }
                this.use('Js');
                const ref = this.functionReference(args[0]);
                if (ref) return call('map', [self(), 'x -> ' + ref.name + '(x)'], indent);

                return call('map', [self(), this.emitLambda(args[0], indent, objType === 'values' ? 'any' : 'string')], indent);
            }
            case 'filter': {
                if (objType !== 'strings' && objType !== 'values') {
                    throw new TranspileError('filter is only supported on lists of strings', node);
                }
                this.use('Js');
                if (!isFunction(args[0]) && this.typeOf(args[0]) === 'predicate') {
                    return call('filter', [self(), this.emitValue(args[0], indent, 'predicate')], indent);
                }
                if (!isFunction(args[0]) || args[0].params.length !== 1) throw new TranspileError('Unsupported filter callback', node);

                return call('filter', [self(), this.emitLambda(args[0], indent, objType === 'values' ? 'any' : 'string')], indent);
            }
            case 'match': {
                const re = args[0];
                if (objType !== 'string' || !re || re.type !== 'Literal' || !re.regex || re.regex.flags) {
                    throw new TranspileError('Unsupported match', node);
                }
                this.use('Js');

                return call('Js.match', [self(), javaString(re.regex.pattern)], indent);
            }
            case 'has':
                if (objType !== 'stringset') throw new TranspileError('Unsupported has', node);

                return call(self() + '.contains', [this.emitValue(args[0], indent, 'string')], indent);
            case 'indexOf':
                if (objType !== 'string') throw new TranspileError('Unsupported indexOf', node);

                return call(self() + '.indexOf', args.map((a) => this.emitValue(a, indent, null)), indent);
            case 'ignoreMatch':
                if (objType !== 'response') throw new TranspileError('Unsupported ignoreMatch', node);

                return self() + '.ignoreMatch()';
            case 'find':
            case 'findIndex':
                if (objType !== 'modes') throw new TranspileError(method + ' is only supported on lists of modes', node);
                this.use('Js');

                return call('Js.' + method, [self(), this.emitLambda(args[0], indent, 'mode')], indent);
            case 'splice': {
                if (objType !== 'modes' && objType !== 'strings') throw new TranspileError('Unsupported splice', node);
                const elementType = objType === 'modes' ? 'mode' : 'string';
                this.use('Js');

                return call('Js.splice', [self(), this.emitValue(args[0], indent, 'number'),
                    args[1] ? this.emitValue(args[1], indent, 'number') : 'Integer.MAX_VALUE']
                    .concat(args.slice(2).map((a) => this.emitValue(a, indent + 1, elementType))), indent);
            }
            case 'includes':
                if (objType !== 'strings' && objType !== 'string') throw new TranspileError('Unsupported includes', node);

                return call(self() + '.contains', [this.emitValue(args[0], indent, objType === 'strings' ? 'string' : null)], indent);
            case 'replace': {
                const pattern = args[0];
                const replacement = this.emitValue(args[1], indent, 'string');
                this.use('Js');
                if (pattern.type === 'Literal' && pattern.regex) {
                    if (pattern.regex.flags.replace('g', '')) {
                        throw new TranspileError('Unsupported regex flags ' + pattern.regex.flags, pattern);
                    }

                    return call('replaceRegex', [self(), javaString(pattern.regex.pattern), replacement,
                        String(pattern.regex.flags.includes('g'))], indent);
                }
                if (pattern.type === 'Literal' && typeof pattern.value === 'string') {
                    return call('replace', [self(), javaString(pattern.value), replacement], indent);
                }
                throw new TranspileError('Unsupported replace pattern', pattern);
            }
            default:
                throw new TranspileError('Unsupported method ' + method, node);
        }
    }
}

/** Appends a suffix to the last line of code, before comments. */
function suffix(code, s) {
    const lines = code.split('\n');
    let i = lines.length - 1;
    while (i > 0 && lines[i].startsWith(COMMENT_LINE)) i--;
    const line = lines[i];
    const mark = line.indexOf(COMMENT_MARK);
    lines[i] = mark < 0 ? line + s : line.slice(0, mark).trimEnd() + s + ' ' + line.slice(mark);

    return lines.join('\n');
}

/** Whether code ends with a comment, so nothing may follow on the same line. */
function endsWithComment(code) {
    const last = code.slice(code.lastIndexOf('\n') + 1);

    return last.includes(COMMENT_MARK) || last.startsWith(COMMENT_LINE);
}

/** Joins arguments with commas, moving to a new line after a comment. */
function joinArgs(codes, indent) {
    let out = '';
    codes.forEach(function (c, i) {
        if (i === 0) {
            out = c;
        } else if (endsWithComment(out)) {
            out = suffix(out, ',') + '\n' + INDENT.repeat(indent + 1) + c;
        } else {
            out += ', ' + c;
        }
    });

    return out;
}

/** A call: name(args...). */
function call(name, codes, indent) {
    return name + '(' + suffix(joinArgs(codes, indent), ')');
}

function width(line) {
    return line.replace(/\t/g, ' '.repeat(TAB_WIDTH)).length;
}

// ---------------------------------------------------------------------------

function headerDocBlock(comment) {
    const lines = comment.value.replace(/^\*?\s*\n/, '').replace(/\s+$/, '').split('\n');

    return ['/**'].concat(lines.map(function (l) {
        const t = safeComment(l.replace(/^\s*\*?\s?/, '').replace(/\*\//g, '*\\/').trimEnd());

        return t ? ' * ' + t.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/@/g, '&#64;') : ' *';
    })).concat([' */']).join('\n');
}

/**
 * Registers the imports of a module with the emitter.
 *
 * - `import * as regex from '../lib/regex.js'`: the regex helpers, org.scrivo.highlight.Regex;
 * - `import * as X from './lib/name.js'`: a shared module of the languages, see transpileModule;
 * - `import name from './name.js'`: the definition of another language.
 */
function registerImports(ast, emitter, options, languageClass) {
    ast.body.filter(function (n) { return n.type === 'ImportDeclaration'; }).forEach(function (imp) {
        const from = imp.source.value.replace(/\.js$/, '');
        const moduleClass = function (module) {
            // a module with the name of a language (lib/java.js) is used by its qualified name
            const qualified = module.className === languageClass || (options.languageClasses || new Set()).has(module.className);

            return { className: qualified ? 'org.scrivo.highlight.languages.lib.' + module.className : module.className, qualified };
        };
        if (/^(\.\.\/)+lib\/regex$/.test(from) && imp.specifiers.length && imp.specifiers.every(function (sp) {
            return sp.type === 'ImportSpecifier';
        })) {
            // import { concat, either } from '../lib/regex.js'
            imp.specifiers.forEach(function (sp) {
                if (!REGEX_FUNCTIONS.includes(sp.imported.name)) throw new TranspileError('Unknown regex function ' + sp.imported.name, sp);
                emitter.imports_.set(sp.local.name, { kind: 'regexfn', exportName: sp.imported.name });
            });

            return;
        }
        if (/^\.\/lib\/[\w-]+$/.test(from) && imp.specifiers.length && imp.specifiers.every(function (sp) {
            return sp.type === 'ImportSpecifier';
        })) {
            // import { NUMERIC } from "./lib/java.js"
            const module = (options.modules || {})[from.slice(2)];
            if (!module) throw new TranspileError('Unknown module ' + imp.source.value, imp);
            imp.specifiers.forEach(function (sp) {
                const type = module.exports[sp.imported.name];
                if (!type) throw new TranspileError('Unknown export ' + sp.imported.name, sp);
                emitter.imports_.set(sp.local.name, Object.assign({ kind: 'binding', exportName: sp.imported.name, type: type },
                    moduleClass(module)));
            });

            return;
        }
        if (imp.specifiers.length !== 1) throw new TranspileError('Unsupported import', imp);
        const spec = imp.specifiers[0];
        const name = spec.local.name;
        if (from === '../lib/regex' && spec.type === 'ImportNamespaceSpecifier') {
            emitter.imports_.set(name, { kind: 'regex' });
        } else if (/^\.\/lib\/[\w-]+$/.test(from) && spec.type === 'ImportNamespaceSpecifier') {
            const module = (options.modules || {})[from.slice(2)];
            if (!module) throw new TranspileError('Unknown module ' + imp.source.value, imp);
            emitter.imports_.set(name, Object.assign({ kind: 'module', exports: module.exports, functions: module.functions },
                moduleClass(module)));
        } else if (/^\.\/[\w-]+$/.test(from) && spec.type === 'ImportDefaultSpecifier') {
            emitter.imports_.set(name, { kind: 'language', className: className(from.slice(2)) });
        } else {
            throw new TranspileError('Unsupported import', imp);
        }
    });
}

/** The import statements of a generated class. */
function javaImports(emitter, code, packageName) {
    // the names used in the code, not in comments or strings
    code = code.replace(/"(?:[^"\\\n]|\\.)*"/g, '""')
        .replace(new RegExp('(' + COMMENT_MARK + '|' + COMMENT_LINE + '[^\\n]*?)//[^\\n]*', 'g'), '')
        .replace(/\/\*[\s\S]*?\*\//g, '');
    const imports = [];
    if (emitter.imports.has('Js')) {
        imports.push('import static org.scrivo.highlight.Js.*;', '');
    }
    const javaUtil = [];
    if (/\bList</.test(code)) javaUtil.push('import java.util.List;');
    if (/\bMap</.test(code)) javaUtil.push('import java.util.Map;');
    if (emitter.imports.has('HashSet')) javaUtil.push('import java.util.HashSet;');
    if (/\bObjects\./.test(code)) javaUtil.push('import java.util.Objects;');
    if (/\bSet</.test(code)) javaUtil.push('import java.util.Set;');
    if (emitter.imports.has('Predicate') || /\bPredicate</.test(code)) javaUtil.push('import java.util.function.Predicate;');
    if (javaUtil.length) imports.push(...javaUtil.sort(), '');
    const own = [];
    if (/\bhljs\b/.test(code)) own.push('import org.scrivo.highlight.Hljs;');
    if (/\bJs\./.test(code)) own.push('import org.scrivo.highlight.Js;');
    if (/\bLanguage\b/.test(code.replace(/LanguageDefinition/g, ''))) own.push('import org.scrivo.highlight.Language;');
    if (packageName === 'org.scrivo.highlight.languages') own.push('import org.scrivo.highlight.LanguageDefinition;');
    if (/\bMode\b/.test(code)) own.push('import org.scrivo.highlight.Mode;');
    if (/\bMatch\b/.test(code)) own.push('import org.scrivo.highlight.Match;');
    if (/\bResponse\b/.test(code)) own.push('import org.scrivo.highlight.Response;');
    if (emitter.imports.has('Regex')) own.push('import org.scrivo.highlight.Regex;');
    emitter.imports_.forEach(function (imp) {
        if ((imp.kind === 'module' || imp.kind === 'binding') && !imp.qualified && emitter.imports.has(imp.className)) {
            own.push('import org.scrivo.highlight.languages.lib.' + imp.className + ';');
        }
    });
    own.sort();

    return imports.concat(own);
}

/**
 * @param {string} source       JS source of the language definition
 * @param {string} languageId   e.g. "c-like"
 * @param {object} [options]    {modules: the shared modules (see transpileModule) by path, like "lib/ecmascript"}
 * @returns {string} Java source
 */
function transpile(source, languageId, options) {
    options = options || {};
    const comments = [];
    const ast = acorn.parse(source, {
        ecmaVersion: 2020,
        sourceType: 'module',
        locations: true,
        onComment: comments,
    });

    const exported = ast.body.find(function (n) { return n.type === 'ExportDefaultDeclaration'; });
    if (!exported || exported.declaration.type !== 'FunctionDeclaration') {
        throw new TranspileError('Expected `export default function(hljs)`', ast);
    }
    const fn = exported.declaration;
    if (fn.params.length !== 1 || fn.params[0].type !== 'Identifier' ||
        (fn.params[0].name !== 'hljs' && freeVariables({ params: [], body: fn.body }).has(fn.params[0].name))) {
        // the parameter may have another name (like `_hljs`) when it is not used
        throw new TranspileError('Expected a single `hljs` parameter', fn);
    }
    // module level functions, used by the definition
    const moduleFunctions = ast.body.filter(function (n) {
        if (n === exported || n.type === 'ImportDeclaration') return false;
        if (n.type !== 'FunctionDeclaration') throw new TranspileError('Unsupported module level ' + n.type, n);

        return true;
    });

    const cm = new Comments(comments, source);
    const header = comments.find(function (c) { return c.type === 'Block' && c.end <= exported.start; });
    // comments before the export (other than the header) are dropped
    comments.filter(function (c) { return c.end <= exported.start; }).forEach(function (c) { cm.used.add(c); });

    const emitter = new Emitter(source, cm, languageId);
    registerImports(ast, emitter, options, className(languageId));
    const moduleScope = { info: analyse({ type: 'BlockStatement', body: moduleFunctions }, []), fn: null, id: 0 };
    emitter.scopes.push(moduleScope);
    const body = emitter.withFunctionScope(fn, function () {
        emitter.returnContext = 'mode';

        return emitter.emitBlock(fn.body.body, 2);
    }, [{ name: 'hljs', type: 'hljs' }]);
    // the modes imported from modules: one new instance for the definition
    const hoisted = [];
    emitter.imports_.forEach(function (imp, local) {
        if (imp.kind === 'binding' && imp.type === 'mode') {
            emitter.use(imp.className);
            hoisted.push(INDENT.repeat(2) + 'Mode ' + javaName(local) + ' = ' + imp.className + '.' + imp.exportName + '();');
        }
    });
    if (hoisted.length) body.unshift(...hoisted, '');
    moduleFunctions.forEach(function (f) {
        if (emitter.methodInfo.has(f)) emitter.emitMethod(f, f.id.name, f);
    });
    // comments after the last statement
    const rest = [];
    cm.take(fn.body.start, fn.body.end).forEach(function (c) {
        cm.lines(c, INDENT.repeat(2)).forEach(function (l) { rest.push(l); });
    });

    const code = 'hljs Mode ' + body.join('\n') + (rest.length ? '\n' + rest.join('\n') : '') + '\n' + emitter.methods.join('\n');
    const imports = javaImports(emitter, code, 'org.scrivo.highlight.languages');

    const cls = className(languageId);
    const out = [
        '/* This file is generated by tools/transpiler from highlight.js src/languages/' + languageId + '.js. */',
        '',
        'package org.scrivo.highlight.languages;',
        '',
    ].concat(imports).concat([
        '',
        header ? headerDocBlock(header) : null,
        'public final class ' + cls + ' implements LanguageDefinition {',
        '',
        INDENT + 'public static final String ID = ' + javaString(languageId) + ';',
        '',
        INDENT + '@Override',
        INDENT + 'public Mode define(Hljs hljs) {',
        body.join('\n') + (rest.length ? '\n' + rest.join('\n') : ''),
        INDENT + '}',
    ]).concat(emitter.methods.length ? ['', emitter.methods.join('\n\n')] : []).concat([
        '}',
        '',
    ]).filter(function (l) { return l !== null; });

    return out.join('\n').split(COMMENT_MARK).join('').split(COMMENT_LINE).join('');
}

/** Whether a variable of a module is used by its other declarations. */
function modeReferenced(statements, name) {
    let found = false;
    const visit = function (node, parent, key) {
        if (found || !node || typeof node.type !== 'string') return;
        if (node.type === 'Identifier' && node.name === name &&
            !(parent && parent.type === 'VariableDeclarator' && key === 'id') &&
            !(parent && parent.type === 'MemberExpression' && key === 'property' && !parent.computed) &&
            !(parent && parent.type === 'Property' && key === 'key')) {
            found = true;

            return;
        }
        for (const k of Object.keys(node)) {
            if (k === 'loc') continue;
            const child = node[k];
            if (Array.isArray(child)) child.forEach(function (c) { visit(c, node, k); });
            else if (child && typeof child.type === 'string') visit(child, node, k);
        }
    };
    statements.forEach(function (st) { visit(st, null, null); });

    return found;
}

/**
 * Transpiles a module shared by language definitions (like src/languages/lib/ecmascript.js)
 * into a Java class with a constant for each variable.
 *
 * @param {string} source     JS source of the module
 * @param {string} moduleId   e.g. "lib/ecmascript"
 * @returns {{className: string, exports: Object<string, string>, java: string}} the class, the types of the
 *     exported constants and the Java source
 */
function transpileModule(source, moduleId) {
    const comments = [];
    const ast = acorn.parse(source, {
        ecmaVersion: 2020,
        sourceType: 'module',
        locations: true,
        onComment: comments,
    });

    const exportedNames = new Set();
    const statements = [];
    ast.body.forEach(function (n) {
        if (n.type === 'ExportNamedDeclaration' && n.declaration) {
            n.declaration.declarations.forEach(function (d) { exportedNames.add(d.id.name); });
            statements.push(n.declaration);
        } else if (n.type === 'ExportNamedDeclaration' && !n.source) {
            n.specifiers.forEach(function (sp) {
                if (sp.local.name !== sp.exported.name) throw new TranspileError('Renamed exports are not supported', sp);
                exportedNames.add(sp.local.name);
            });
        } else if (n.type === 'VariableDeclaration') {
            statements.push(n);
        } else if (n.type !== 'ImportDeclaration') {
            throw new TranspileError('Unsupported module level ' + n.type, n);
        }
    });

    const cm = new Comments(comments, source);
    const emitter = new Emitter(source, cm, moduleId);
    registerImports(ast, emitter, {}, null);
    const scope = { info: analyse({ type: 'BlockStatement', body: statements }, []), fn: null, id: 0 };
    emitter.scopes.push(scope);

    const exports = {};
    const functions = {};
    const lines = [];
    let prevLine = null;
    statements.forEach(function (stmt) {
        stmt.declarations.forEach(function (d) {
            if (d.init && isFunction(d.init)) {
                // a function, as a static method
                const info = emitter.methodFor(d.init, d.id.name);
                if (info.captured.length) throw new TranspileError('Functions of modules may not use variables', d);
                info.access = exportedNames.has(d.id.name) ? 'public' : 'private';
                if (exportedNames.has(d.id.name)) {
                    exports[d.id.name] = 'function';
                    functions[d.id.name] = info.returnType;
                }
                emitter.emitMethod(d.init, d.id.name, stmt);

                return;
            }
            const type = emitter.identifierType(d.id.name, d);
            const javaT = javaType(type, d);
            if (exportedNames.has(d.id.name)) exports[d.id.name] = type;
            if (lines.length) lines.push('');
            emitter.emitLeadingComments(lines, d, 1, prevLine);
            const access = exportedNames.has(d.id.name) ? 'public' : 'private';
            if (type === 'mode') {
                // A mode is compiled, so it can't be shared by languages (in JavaScript it is, but each
                // language compiles it the same): a method creates a new one for each language definition.
                if (!exportedNames.has(d.id.name) || modeReferenced(statements, d.id.name)) {
                    throw new TranspileError('Only exported modes that are not used by the module are supported', d);
                }
                lines.push(INDENT + '/** A new instance of the mode, for each language definition. */');
                lines.push(INDENT + 'public static Mode ' + javaName(d.id.name) + '() {');
                lines.push(INDENT + INDENT + 'return ' + suffix(emitter.emitValue(d.init, 2, type), ';'));
                lines.push(INDENT + '}');
                emitter.emitTrailingComment(lines, stmt);
                prevLine = cm.lineOf(stmt.end);

                return;
            }
            let value = emitter.emitValue(d.init, 1, type);
            if (type === 'modes' || type === 'strings' || type === 'values' || type === 'map' || type === 'objmap') {
                // constants shared by the languages are not to be changed
                value = call(type === 'map' || type === 'objmap' ? 'Map.copyOf' : 'List.copyOf', [value], 1);
            }
            lines.push(INDENT + access + ' static final ' + javaT + ' ' + javaName(d.id.name) + ' = ' + suffix(value, ';'));
            emitter.emitTrailingComment(lines, stmt);
            prevLine = cm.lineOf(stmt.end);
        });
    });

    const cls = className(moduleId.replace(/^lib\//, ''));
    const code = lines.join('\n') + (emitter.methods.length ? '\n\n' + emitter.methods.join('\n\n') : '');
    const imports = javaImports(emitter, code, 'org.scrivo.highlight.languages.lib');
    const out = [
        '/* This file is generated by tools/transpiler from highlight.js src/languages/' + moduleId + '.js. */',
        '',
        'package org.scrivo.highlight.languages.lib;',
        '',
    ].concat(imports.length ? imports.concat(['']) : []).concat([
        '/**',
        ' * Constants shared by language definitions.',
        ' */',
        'public final class ' + cls + ' {',
        '',
        INDENT + 'private ' + cls + '() {',
        INDENT + '}',
        '',
        code,
        '}',
        '',
    ]);

    return {
        className: cls,
        exports: exports,
        functions: functions,
        java: out.join('\n').split(COMMENT_MARK).join('').split(COMMENT_LINE).join(''),
    };
}

module.exports = { transpile: transpile, transpileModule: transpileModule, className: className, TranspileError: TranspileError };
