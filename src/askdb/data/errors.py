"""Errors raised while importing or querying a dataset."""


class DatasetError(Exception):
    """Base class for dataset problems the user must see."""


class UnsupportedFileError(DatasetError):
    pass


class FileTooLargeError(DatasetError):
    pass


class DatasetImportError(DatasetError):
    pass


class QueryError(DatasetError):
    pass


class QueryTimeoutError(QueryError):
    pass
