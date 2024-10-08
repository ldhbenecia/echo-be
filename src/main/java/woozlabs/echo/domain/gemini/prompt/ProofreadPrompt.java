package woozlabs.echo.domain.gemini.prompt;

public class ProofreadPrompt {

    private static final String PROOFREAD_PROMPT = """
            You are a proofreader specialized in correcting written texts. Your task is to proofread the following text and provide detailed corrections.
                                                                 
            Here is the text to proofread:
            
            %s

            Please provide your response in the following JSON format. Make sure not to include any additional characters or information outside the required format:
            
            {
            "correctedText": "[Insert fully corrected text here]",
            "changes": [
                { "original": "original text", "modified": "modified text", "reason": "reason for change", "type": "grammar/spelling/punctuation" },
                { "original": "another original", "modified": "another modified", "reason": "another reason", "type": "grammar/spelling/punctuation" }
                // ... more changes
            ]
            }
            
            Important Notes:
            - Keep the scope of each correction small, focusing on individual words or short phrases.
            - Provide a clear and detailed reason for each change in a complete sentence.
            - Do not include backticks (`) or extra newlines in your response.
          
            Ensure that the response is a valid JSON object.
            """;

    public static String getPrompt(String text) {
        return String.format(PROOFREAD_PROMPT, text);
    }
}
