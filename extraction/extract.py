from pypdf import PdfReader

reader = PdfReader("SR.pdf")

print(len(reader.pages))

text = ""
for i in range(200, 232):
    page = reader.pages[i]
    page_text = page.extract_text()
    text += page_text

print(text)

# Save the extracted text to a file
with open("extracted_text.txt", "w", encoding="utf-8") as f:
    f.write(text)
