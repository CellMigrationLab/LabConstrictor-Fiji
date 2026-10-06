"""A tool with two required images: the first open image is the first parameter, the second open image the second."""

from labconstrictor_tools import Image, Scalars, tool


@tool("Two images")
def two_images(first: Image, second: Image) -> Scalars:
    return {"same_shape": first.shape == second.shape}
