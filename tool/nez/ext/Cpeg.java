package nez.ext;

import java.io.IOException;

import nez.main.Command;
import nez.main.CommandContext;
import nez.parser.Parser;
import nez.parser.ParserGenerator;

public class Cpeg extends Command {

	@Override
	public void exec(CommandContext config) throws IOException {
		ParserGenerator pgen = newParserGenerator(config);
		Parser p = config.newParser();
		pgen.generate(p.getParserGrammar());
	}

	protected ParserGenerator newParserGenerator(CommandContext config) {
		return config.newParserGenerator(nez.parser.generator.PEGGenerator.class);
	}
}
