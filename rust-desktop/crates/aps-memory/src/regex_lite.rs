//! Bounded, dependency-free regular-expression subset for the class filter.
//!
//! The Compose oracle uses java.util.regex with partial-match semantics. This
//! module keeps the same observable behavior for the constructs a class-name
//! filter needs: literals, dot, character classes, anchors, alternation,
//! groups, escapes and *, +, ?, {m}, {m,}, {m,n} quantifiers. Unsupported
//! constructs (lookarounds, backreferences) make the pattern invalid, which
//! matches the oracle's "invalid regex matches nothing" behavior. Matching is
//! budgeted so a hostile pattern cannot stall the UI thread.

const MAX_STEPS: usize = 250_000;

pub(crate) fn contains(pattern: &str, ignore_case: bool, text: &str) -> bool {
    let Ok(expr) = Parser::new(pattern).parse() else {
        return false;
    };
    let chars: Vec<char> = text.chars().collect();
    let mut context = MatchContext {
        ignore_case,
        chars: &chars,
        steps: 0,
    };
    for start in 0..=chars.len() {
        if context.matches_at(&expr, start) {
            return true;
        }
        if context.exhausted() {
            return false;
        }
    }
    false
}

struct Expr(Vec<Vec<Term>>);

enum Term {
    Atom(Atom),
    Repeat(Atom, u32, Option<u32>),
}

enum Atom {
    Literal(char),
    Any,
    Class(CharClass),
    Group(Box<Expr>),
    Start,
    End,
}

struct CharClass {
    negated: bool,
    ranges: Vec<(char, char)>,
    sets: Vec<SetKind>,
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum SetKind {
    Digit,
    NotDigit,
    Word,
    NotWord,
    Space,
    NotSpace,
}

impl SetKind {
    fn from_escape(escape: char) -> Option<Self> {
        Some(match escape {
            'd' => Self::Digit,
            'D' => Self::NotDigit,
            'w' => Self::Word,
            'W' => Self::NotWord,
            's' => Self::Space,
            'S' => Self::NotSpace,
            _ => return None,
        })
    }

    fn matches(self, c: char) -> bool {
        let lower = c.is_ascii_digit();
        let word = c.is_alphanumeric() || c == '_';
        let space = c.is_whitespace();
        match self {
            Self::Digit => lower,
            Self::NotDigit => !lower,
            Self::Word => word,
            Self::NotWord => !word,
            Self::Space => space,
            Self::NotSpace => !space,
        }
    }
}

impl CharClass {
    fn matches(&self, c: char, ignore_case: bool) -> bool {
        fn base(class: &CharClass, c: char) -> bool {
            class
                .ranges
                .iter()
                .any(|(start, end)| *start <= c && c <= *end)
                || class.sets.iter().any(|set| set.matches(c))
        }
        let mut matched = base(self, c);
        if ignore_case {
            matched |= base(self, c.to_ascii_lowercase());
            matched |= base(self, c.to_ascii_uppercase());
        }
        matched != self.negated
    }
}

struct MatchContext<'a> {
    ignore_case: bool,
    chars: &'a [char],
    steps: usize,
}

impl MatchContext<'_> {
    fn step(&mut self) -> bool {
        self.steps += 1;
        self.steps <= MAX_STEPS
    }

    fn exhausted(&self) -> bool {
        self.steps > MAX_STEPS
    }

    fn matches_at(&mut self, expr: &Expr, start: usize) -> bool {
        for sequence in &expr.0 {
            if !self.expand(sequence, vec![start]).is_empty() {
                return true;
            }
        }
        false
    }

    fn expand(&mut self, terms: &[Term], starts: Vec<usize>) -> Vec<usize> {
        let mut current = starts;
        for term in terms {
            let mut next = Vec::new();
            for position in current {
                match term {
                    Term::Atom(atom) => self.expand_atom(atom, position, &mut next),
                    Term::Repeat(atom, min, max) => {
                        self.expand_repeat(atom, *min, *max, position, &mut next);
                    }
                }
            }
            dedupe(&mut next);
            if next.is_empty() {
                return next;
            }
            current = next;
        }
        current
    }

    fn expand_atom(&mut self, atom: &Atom, position: usize, out: &mut Vec<usize>) {
        if !self.step() {
            return;
        }
        match atom {
            Atom::Literal(expected) => {
                if position < self.chars.len()
                    && char_eq(self.chars[position], *expected, self.ignore_case)
                {
                    out.push(position + 1);
                }
            }
            Atom::Any => {
                if position < self.chars.len() {
                    out.push(position + 1);
                }
            }
            Atom::Class(class) => {
                if position < self.chars.len()
                    && class.matches(self.chars[position], self.ignore_case)
                {
                    out.push(position + 1);
                }
            }
            Atom::Group(expr) => {
                for sequence in &expr.0 {
                    let ends = self.expand(sequence, vec![position]);
                    out.extend(ends);
                }
            }
            Atom::Start => {
                if position == 0 {
                    out.push(position);
                }
            }
            Atom::End => {
                if position == self.chars.len() {
                    out.push(position);
                }
            }
        }
    }

    fn expand_repeat(
        &mut self,
        atom: &Atom,
        min: u32,
        max: Option<u32>,
        position: usize,
        out: &mut Vec<usize>,
    ) {
        let mut level = vec![position];
        if min == 0 {
            out.push(position);
        }
        let cap = max.map_or(self.chars.len() + 1, |max| max as usize);
        for count in 1..=cap {
            let mut next = Vec::new();
            for current in &level {
                self.expand_atom(atom, *current, &mut next);
            }
            dedupe(&mut next);
            if next.is_empty() {
                return;
            }
            if count as u32 >= min {
                out.extend(next.iter().copied());
            }
            level = next;
        }
    }
}

fn dedupe(values: &mut Vec<usize>) {
    values.sort_unstable();
    values.dedup();
}

fn char_eq(left: char, right: char, ignore_case: bool) -> bool {
    if ignore_case {
        left.eq_ignore_ascii_case(&right)
    } else {
        left == right
    }
}

struct Parser {
    chars: Vec<char>,
    position: usize,
}

impl Parser {
    fn new(pattern: &str) -> Self {
        Self {
            chars: pattern.chars().collect(),
            position: 0,
        }
    }

    fn parse(mut self) -> Result<Expr, ()> {
        let expr = self.parse_alternation()?;
        if self.position != self.chars.len() {
            return Err(());
        }
        Ok(expr)
    }

    fn peek(&self) -> Option<char> {
        self.chars.get(self.position).copied()
    }

    fn peek_at(&self, offset: usize) -> Option<char> {
        self.chars.get(self.position + offset).copied()
    }

    fn next(&mut self) -> Option<char> {
        let value = self.peek();
        self.position += 1;
        value
    }

    fn parse_alternation(&mut self) -> Result<Expr, ()> {
        let mut sequences = vec![self.parse_sequence()?];
        while self.peek() == Some('|') {
            self.position += 1;
            sequences.push(self.parse_sequence()?);
        }
        Ok(Expr(sequences))
    }

    fn parse_sequence(&mut self) -> Result<Vec<Term>, ()> {
        let mut terms = Vec::new();
        while let Some(c) = self.peek() {
            if c == ')' || c == '|' {
                break;
            }
            terms.push(self.parse_term()?);
        }
        Ok(terms)
    }

    fn parse_term(&mut self) -> Result<Term, ()> {
        let atom = self.parse_atom()?;
        let (min, max) = match self.peek() {
            Some('*') => {
                self.position += 1;
                (0, None)
            }
            Some('+') => {
                self.position += 1;
                (1, None)
            }
            Some('?') => {
                self.position += 1;
                (0, Some(1))
            }
            Some('{') => self.parse_braces()?,
            _ => return Ok(Term::Atom(atom)),
        };
        // Lazy and possessive suffixes do not change whether a match exists.
        if matches!(self.peek(), Some('?') | Some('+')) {
            self.position += 1;
        }
        Ok(Term::Repeat(atom, min, max))
    }

    fn parse_braces(&mut self) -> Result<(u32, Option<u32>), ()> {
        self.position += 1;
        let min = self.parse_digits()?.ok_or(())?;
        let max = if self.peek() == Some(',') {
            self.position += 1;
            self.parse_digits()?
        } else {
            Some(min)
        };
        if self.peek() != Some('}') {
            return Err(());
        }
        self.position += 1;
        if max.is_some_and(|max| max < min) {
            return Err(());
        }
        Ok((min, max))
    }

    fn parse_digits(&mut self) -> Result<Option<u32>, ()> {
        let start = self.position;
        while self.peek().is_some_and(|c| c.is_ascii_digit()) {
            self.position += 1;
        }
        if start == self.position {
            return Ok(None);
        }
        let text: String = self.chars[start..self.position].iter().collect();
        text.parse::<u32>().map(Some).map_err(|_| ())
    }

    fn parse_atom(&mut self) -> Result<Atom, ()> {
        let c = self.next().ok_or(())?;
        Ok(match c {
            '.' => Atom::Any,
            '^' => Atom::Start,
            '$' => Atom::End,
            '(' => {
                if self.peek() == Some('?') {
                    if self.peek_at(1) == Some(':') {
                        self.position += 2;
                    } else {
                        return Err(());
                    }
                }
                let expr = self.parse_alternation()?;
                if self.next() != Some(')') {
                    return Err(());
                }
                Atom::Group(Box::new(expr))
            }
            '[' => Atom::Class(self.parse_class()?),
            '\\' => self.parse_escape_atom()?,
            ')' | '|' | '*' | '+' | '?' | '{' | '}' => return Err(()),
            literal => Atom::Literal(literal),
        })
    }

    fn parse_escape_atom(&mut self) -> Result<Atom, ()> {
        let escape = self.next().ok_or(())?;
        if let Some(set) = SetKind::from_escape(escape) {
            return Ok(Atom::Class(CharClass {
                negated: false,
                ranges: Vec::new(),
                sets: vec![set],
            }));
        }
        Ok(Atom::Literal(escaped_literal(escape)?))
    }

    fn parse_class(&mut self) -> Result<CharClass, ()> {
        let negated = if self.peek() == Some('^') {
            self.position += 1;
            true
        } else {
            false
        };
        let mut class = CharClass {
            negated,
            ranges: Vec::new(),
            sets: Vec::new(),
        };
        if self.peek() == Some(']') {
            class.ranges.push((']', ']'));
            self.position += 1;
        }
        loop {
            let c = self.next().ok_or(())?;
            if c == ']' {
                break;
            }
            let item = if c == '\\' {
                let escape = self.next().ok_or(())?;
                if let Some(set) = SetKind::from_escape(escape) {
                    class.sets.push(set);
                    continue;
                }
                escaped_literal(escape)?
            } else {
                c
            };
            if self.peek() == Some('-') && self.peek_at(1).is_some_and(|next| next != ']') {
                self.position += 1;
                let end = self.next().ok_or(())?;
                let end = if end == '\\' {
                    escaped_literal(self.next().ok_or(())?)?
                } else {
                    end
                };
                if end < item {
                    return Err(());
                }
                class.ranges.push((item, end));
            } else {
                class.ranges.push((item, item));
            }
        }
        Ok(class)
    }
}

fn escaped_literal(escape: char) -> Result<char, ()> {
    Ok(match escape {
        'n' => '\n',
        'r' => '\r',
        't' => '\t',
        'f' => '\u{0c}',
        'v' => '\u{0b}',
        '0' => '\0',
        c if c.is_ascii_alphanumeric() => return Err(()),
        c => c,
    })
}
